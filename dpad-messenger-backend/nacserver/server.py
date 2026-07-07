"""Validation-data relay server.

Runs the nac emulation off-device (the flip phone can't emulate an x86-64 Apple
binary) and serves validation data over the exact HTTP contract the Android
client speaks (`smarttxt/.../relay/HttpValidationDataRelay.kt`):

    GET  /health           -> 200 {"status":"ok"}
    POST /validation-data  -> 200 {"validationDataB64": "<base64>"}
         body: {"platformSerialNumber","mlb","rom","productName","osBuildNum",...}
         header (optional): Authorization: Bearer <token>

This is the plan's §2.6 **option 2**: one shared Mac identity serving the fleet.
The operator supplies:
  * a captured dumb file (OABS) — the Mac hardware identity, and
  * the matching IMDAppleServices binary (extracted from their own Mac).
Neither is shipped in this repo.

Run:
    python -m nacserver.server --dumb ./dumb --binary ./IMDAppleServices \\
        --host 0.0.0.0 --port 8080 [--token SECRET]
"""
import argparse
import base64
import json
import logging
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .hardware import MacOSConfig
from . import nac

log = logging.getLogger("nacserver")


class Generator:
    """Owns the identity + binary and generates validation data, with a short
    cache (validation data is short-lived but stable for a few minutes, so we
    avoid re-emulating on every device poll)."""

    def __init__(self, config: MacOSConfig, binary: bytes, cache_seconds: int = 120):
        self.config = config
        self.binary = binary
        self.cache_seconds = cache_seconds
        self._lock = threading.Lock()
        self._cached = None
        self._cached_at = 0.0

    def validation_data(self) -> bytes:
        with self._lock:
            now = time.time()
            if self._cached and now - self._cached_at < self.cache_seconds:
                return self._cached
            data = nac.generate_validation_data(self.config, self.binary)
            self._cached = data
            self._cached_at = now
            return data


def make_handler(gen: Generator, token: str | None):
    class Handler(BaseHTTPRequestHandler):
        server_version = "nacserver/0.1"

        def _json(self, code: int, obj: dict):
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            if not token:
                return True
            return self.headers.get("Authorization") == f"Bearer {token}"

        def log_message(self, fmt, *args):
            log.info("%s - %s", self.address_string(), fmt % args)

        def do_GET(self):
            if self.path.rstrip("/") == "/health":
                self._json(200, {"status": "ok"})
            else:
                self._json(404, {"error": "not found"})

        def do_POST(self):
            if self.path.rstrip("/") != "/validation-data":
                self._json(404, {"error": "not found"})
                return
            if not self._authorized():
                self._json(401, {"error": "unauthorized"})
                return
            # The posted hardware identity is advisory: this relay serves the
            # identity it was configured with. Optionally sanity-check the serial.
            length = int(self.headers.get("Content-Length", 0) or 0)
            try:
                payload = json.loads(self.rfile.read(length) or b"{}")
            except Exception:
                payload = {}
            want = payload.get("platformSerialNumber")
            have = gen.config.inner.platform_serial_number
            if want and want != have:
                log.warning("requested serial %s != configured %s (serving configured)", want, have)
            try:
                data = gen.validation_data()
            except Exception as e:  # noqa: BLE001
                log.exception("validation-data generation failed")
                self._json(502, {"error": f"generation failed: {e}"})
                return
            self._json(200, {"validationDataB64": base64.b64encode(data).decode()})

    return Handler


def main(argv=None):
    p = argparse.ArgumentParser(description="SmartTxt validation-data relay (nac emulation)")
    p.add_argument("--dumb", required=True, help="path to the OABS dumb file (base64)")
    p.add_argument("--binary", required=True, help="path to the fat IMDAppleServices binary")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8080)
    p.add_argument("--token", default=None, help="optional bearer token clients must send")
    p.add_argument("--cache-seconds", type=int, default=120)
    p.add_argument("-v", "--verbose", action="store_true")
    args = p.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )

    config = MacOSConfig.load(args.dumb)
    with open(args.binary, "rb") as f:
        binary = f.read()
    log.info(
        "loaded identity model=%s serial=%s build=%s; binary=%d bytes",
        config.inner.product_name,
        config.inner.platform_serial_number,
        config.inner.os_build_num,
        len(binary),
    )

    gen = Generator(config, binary, cache_seconds=args.cache_seconds)
    # Warm the cache once at boot so the first device poll is fast and any
    # identity/binary problem surfaces immediately, not on first request.
    try:
        n = len(gen.validation_data())
        log.info("warm-up ok: generated %d bytes of validation data", n)
    except Exception:  # noqa: BLE001
        log.exception("warm-up failed — check the binary + dumb file")

    httpd = ThreadingHTTPServer((args.host, args.port), make_handler(gen, args.token))
    log.info("serving on http://%s:%d  (health: /health, data: POST /validation-data)",
             args.host, args.port)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        log.info("shutting down")


if __name__ == "__main__":
    main()
