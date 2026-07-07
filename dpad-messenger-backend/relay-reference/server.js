#!/usr/bin/env node
/*
 * Reference SmartTxt relay server implementing the RelayProtocol that
 * RelayWebSocketTransport speaks (transport/RelayProtocol.kt + RELAY_PROTOCOL.md).
 *
 * It is the network-path twin of the in-process MockRelayTransport: demo chats,
 * echoes sends with delivered/read status, pushes simulated incoming messages
 * and tapbacks. Use it to end-to-end test the REAL WebSocket client before your
 * production relay exists, and as a precise template for that relay.
 *
 *   npm install && node server.js          # listens on ws://localhost:8765
 *
 * Point the app at it:  SmartTxtConfig.relayBaseUrl = "http://<host>:8765"
 * (or type the URL on the setup screen). The client upgrades http→ws and
 * connects to /smarttxt/v1/socket.
 *
 * A REAL relay replaces the demo data + the simulate loop with calls into
 * rustpush/absinthe on a Mac (or the BlueBubbles server). The frame contract
 * stays exactly as below.
 */
const http = require("http");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 8765;
const PATH = "/smarttxt/v1/socket";
const ME = "me";

// ---- demo state ------------------------------------------------------------
let seq = 1000;
const guid = (p) => `${p}_${++seq}`;

function seedChats() {
  return {
    chat_mom: { guid: "chat_mom", displayName: "Mom", isGroup: false,
      participants: [{ address: "tel:+15555550111", displayName: "Mom" }], unread: false },
    chat_sam: { guid: "chat_sam", displayName: "Sam Rivera", isGroup: false,
      participants: [{ address: "mailto:sam@icloud.com", displayName: "Sam Rivera" }], unread: false },
    chat_trip: { guid: "chat_trip", displayName: "Weekend Trip", isGroup: true,
      participants: [
        { address: "mailto:jules@icloud.com", displayName: "Jules" },
        { address: "mailto:sam@icloud.com", displayName: "Sam Rivera" },
        { address: "tel:+15555550133", displayName: "Priya Patel" },
      ], unread: true },
  };
}
function seedMessages() {
  const now = Date.now();
  const m = (chatGuid, sender, isFromMe, text, agoMs, extra = {}) => ({
    guid: guid("msg"), chatGuid, senderAddress: sender, isFromMe, text,
    timestampMs: now - agoMs, service: "iMessage",
    dateDelivered: isFromMe ? now - agoMs : 0, dateRead: isFromMe ? now - agoMs : 0, ...extra,
  });
  return {
    chat_mom: [
      m("chat_mom", "tel:+15555550111", false, "Did you make it home okay?", 86400000),
      m("chat_mom", ME, true, "Yep, just walked in.", 86000000),
      m("chat_mom", "tel:+15555550111", false, "Call me when you get a sec ❤️", 7200000),
    ],
    chat_sam: [
      m("chat_sam", "mailto:sam@icloud.com", false, "Did you see the game last night?", 3600000),
      m("chat_sam", ME, true, "Unreal finish 🏀", 3500000),
    ],
    chat_trip: [
      m("chat_trip", "mailto:jules@icloud.com", false, "Who's driving Saturday?", 1800000),
      m("chat_trip", "mailto:sam@icloud.com", false, "I can take my car 🚗", 1700000),
      m("chat_trip", "tel:+15555550133", false, "Booked the cabin — confirmation came through.", 1500000),
    ],
  };
}

const chats = seedChats();
const messages = seedMessages();
const contacts = [
  { name: "Mom", address: "tel:+15555550111" },
  { name: "Sam Rivera", address: "mailto:sam@icloud.com" },
  { name: "Jules", address: "mailto:jules@icloud.com" },
  { name: "Priya Patel", address: "tel:+15555550133" },
];

// ---- server ----------------------------------------------------------------
const server = http.createServer();
const wss = new WebSocketServer({ server, path: PATH });

wss.on("connection", (ws) => {
  console.log("client connected");
  const send = (obj) => ws.readyState === ws.OPEN && ws.send(JSON.stringify(obj));
  const ack = (id, data = {}) => send({ type: "ack", id, ok: true, data });
  const nack = (id, error) => send({ type: "ack", id, ok: false, error });

  ws.on("message", (raw) => {
    let f;
    try { f = JSON.parse(raw.toString()); } catch { return; }
    const { type, id } = f;
    switch (type) {
      case "ping": return; // keepalive, no reply
      case "register":
        return ack(id, { handles: [`mailto:${f.appleId || "demo@icloud.com"}`, "tel:+15555550100"] });
      case "get_chats":
        return ack(id, { chats: Object.values(chats) });
      case "get_messages": {
        const all = (messages[f.chatGuid] || []).slice().sort((a, b) => a.timestampMs - b.timestampMs);
        const filtered = f.beforeMs ? all.filter((x) => x.timestampMs < f.beforeMs) : all;
        return ack(id, { messages: filtered.slice(-(f.limit || 50)) });
      }
      case "send_text": {
        const g = guid("msg");
        const msg = { guid: g, chatGuid: f.chatGuid, tempGuid: f.tempGuid, senderAddress: ME,
          isFromMe: true, text: f.text, timestampMs: Date.now(), service: "iMessage",
          replyToGuid: f.replyToGuid || "" };
        (messages[f.chatGuid] = messages[f.chatGuid] || []).push(msg);
        ack(id, { guid: g });
        // Apple delivery lifecycle.
        setTimeout(() => send({ type: "message_status", chatGuid: f.chatGuid, guid: g, tempGuid: f.tempGuid, status: "delivered" }), 600);
        setTimeout(() => send({ type: "message_status", chatGuid: f.chatGuid, guid: g, tempGuid: f.tempGuid, status: "read" }), 1800);
        return;
      }
      case "send_tapback":
        ack(id, {});
        return send({ type: "tapback", chatGuid: f.chatGuid, targetGuid: f.targetGuid,
          emoji: f.emoji, senderAddress: ME, isFromMe: true, remove: !!f.remove });
      case "edit_message": ack(id, {}); return;
      case "unsend_message": ack(id, {}); return;
      case "mark_read":
        if (chats[f.chatGuid]) chats[f.chatGuid].unread = false;
        return ack(id, {});
      case "set_typing":
        return ack(id, {});
      case "get_contacts":
        return ack(id, { contacts });
      case "create_chat": {
        const g = guid("chat");
        const chat = { guid: g, displayName: f.title || (f.addresses || []).map((a) => a.split(":")[1]).join(", "),
          isGroup: (f.addresses || []).length > 1,
          participants: (f.addresses || []).map((a) => ({ address: a, displayName: a.split(":")[1] })) };
        chats[g] = chat; messages[g] = [];
        return ack(id, { chat });
      }
      case "download_attachment": return ack(id, { dataB64: "" });
      case "reauth": return ack(id, {});
      default: return nack(id, `unknown type: ${type}`);
    }
  });

  // Simulate an incoming message every 15s so notifications/unread tick.
  const incoming = [
    ["chat_mom", "tel:+15555550111", "Don't forget dinner Sunday!"],
    ["chat_sam", "mailto:sam@icloud.com", "Wanna grab lunch tomorrow?"],
    ["chat_trip", "mailto:jules@icloud.com", "Just sent the packing list 📋"],
  ];
  let i = 0;
  const timer = setInterval(() => {
    const [chatGuid, sender, text] = incoming[i++ % incoming.length];
    const msg = { guid: guid("msg"), chatGuid, senderAddress: sender, isFromMe: false,
      text, timestampMs: Date.now(), service: "iMessage", dateDelivered: Date.now() };
    (messages[chatGuid] = messages[chatGuid] || []).push(msg);
    send({ type: "new_message", message: msg });
  }, 15000);

  ws.on("close", () => { clearInterval(timer); console.log("client disconnected"); });
});

server.listen(PORT, () => console.log(`reference relay on ws://localhost:${PORT}${PATH}`));
