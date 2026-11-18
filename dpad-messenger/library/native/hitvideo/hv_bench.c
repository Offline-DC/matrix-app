/*
 * hvbench -- decode a video the way smart txt's converter will, and report speed.
 *
 *   adb push hvbench lib*_hit.so /data/local/tmp/hv/
 *   adb shell "cd /data/local/tmp/hv && LD_LIBRARY_PATH=. ./hvbench <video> [threads] [fast 0|1] [long-edge]"
 */
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include "hv_core.h"

static double now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec / 1e9;
}

static int even16(int v) { int r = (v + 8) / 16 * 16; return r < 16 ? 16 : r; }

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s video [threads=4] [fast=1] [long_edge=640]\n", argv[0]); return 2; }
    int threads = argc > 2 ? atoi(argv[2]) : 4;
    int fast = argc > 3 ? atoi(argv[3]) : 1;
    int edge = argc > 4 ? atoi(argv[4]) : 640;

    char err[256] = {0};
    double t0 = now();
    HV *hv = hv_open(argv[1], threads, fast, err, sizeof(err));
    if (!hv) { fprintf(stderr, "open failed: %s\n", err); return 1; }
    int info[HV_INFO_LEN];
    hv_info(hv, info);
    int w = info[HV_INFO_WIDTH], h = info[HV_INFO_HEIGHT];
    int ow, oh;
    if (w >= h) { ow = even16(edge); oh = even16((int) ((double) edge * h / w)); }
    else { oh = even16(edge); ow = even16((int) ((double) edge * w / h)); }
    printf("video %dx%d, %d-bit, %d/%d fps, %d ms, rotation %d, frames %d, codec %d, trc %d\n",
           w, h, info[HV_INFO_BIT_DEPTH], info[HV_INFO_FPS_NUM], info[HV_INFO_FPS_DEN],
           info[HV_INFO_DURATION_MS], info[HV_INFO_ROTATION], info[HV_INFO_FRAMES],
           info[HV_INFO_CODEC_ID], info[HV_INFO_COLOR_TRC]);
    printf("threads %d, fast %d, output %dx%d, open took %.2f s\n", threads, fast, ow, oh, now() - t0);
    hv_set_output(hv, ow, oh);
    int sz = hv_frame_size(hv);
    unsigned char *buf = malloc((size_t) sz);
    int n = 0;
    int64_t pts = 0, last = 0;
    double t1 = now();
    int r;
    while ((r = hv_next(hv, buf, sz, &pts)) > 0) {
        n++;
        last = pts;
        if (n % 60 == 0) { printf("  %d frames, %.1f fps\n", n, n / (now() - t1)); fflush(stdout); }
    }
    double dt = now() - t1;
    printf("%s after %d frames: %.2f s, %.1f fps decode+scale (video is %.1f s long -> %.2fx real time)\n",
           r < 0 ? "ERROR" : "done", n, dt, n / dt, last / 1e6, dt > 0 ? (last / 1e6) / dt : 0);
    free(buf);
    hv_close(hv);
    return r < 0 ? 1 : 0;
}
