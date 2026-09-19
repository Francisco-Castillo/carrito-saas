#!/usr/bin/env node
// WhatsApp Cloud API webhook simulator (T1 of odd/tasks/whatsapp-inbound.md).
//
// Builds the Meta "messages" webhook payload, signs it with the app secret
// (HMAC-SHA256 over the raw body, X-Hub-Signature-256 header) and POSTs it to
// a running instance, so the real verification path is exercised end to end
// instead of staying as dead code.
//
// Usage:
//   node odd/tasks/tools/whatsapp-simulator.mjs [--print-payload]
//
// Environment variables (all optional):
//   WHATSAPP_WEBHOOK_URL   target URL (default http://localhost:9090/api/whatsapp/webhook)
//   WHATSAPP_APP_SECRET    app secret (default local-dev-secret, the same
//                          default as api/src/main/resources/application.yml)
//
// --print-payload writes the payload bytes to stdout without POSTing. It
// exists so a reader can prove the payload here is byte-identical to the
// META_TEXT_MESSAGE_PAYLOAD fixture in
// api/src/test/java/com/carrito/saas/WhatsappWebhookContractTests.java.
//
// Exit status: 0 when the server answers 200, 1 otherwise (bad signature,
// wrong token configuration, server down, etc.).

import { createHmac } from "node:crypto";

const TARGET_URL = process.env.WHATSAPP_WEBHOOK_URL ?? "http://localhost:9090/api/whatsapp/webhook";
const APP_SECRET = process.env.WHATSAPP_APP_SECRET ?? "local-dev-secret";

// Keep this payload BYTE-IDENTICAL to META_TEXT_MESSAGE_PAYLOAD in
// api/src/test/java/com/carrito/saas/WhatsappWebhookContractTests.java:
// same fields, same values, same formatting, no trailing newline.
const PAYLOAD = `{
  "object": "whatsapp_business_account",
  "entry": [
    {
      "id": "1111111111",
      "changes": [
        {
          "field": "messages",
          "value": {
            "messaging_product": "whatsapp",
            "metadata": {
              "display_phone_number": "5491122334455",
              "phone_number_id": "2222222222"
            },
            "contacts": [
              {
                "profile": {
                  "name": "Juan"
                },
                "wa_id": "5491122334455"
              }
            ],
            "messages": [
              {
                "from": "5491122334455",
                "id": "wamid.HBg...",
                "timestamp": "1726000000",
                "type": "text",
                "text": {
                  "body": "hola quiero 2 milanesas con papas y una coca"
                }
              }
            ]
          }
        }
      ]
    }
  ]
}`;

if (process.argv.includes("--print-payload")) {
  process.stdout.write(PAYLOAD);
  process.exit(0);
}

const signature =
  "sha256=" + createHmac("sha256", APP_SECRET).update(PAYLOAD, "utf8").digest("hex");

const response = await fetch(TARGET_URL, {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    "X-Hub-Signature-256": signature,
  },
  body: PAYLOAD,
});

const body = await response.text();

console.log(`POST ${TARGET_URL}`);
console.log(`X-Hub-Signature-256: ${signature}`);
console.log(`status: ${response.status}`);
console.log(`body: ${body}`);

if (response.status !== 200) {
  console.error(`FAIL: expected 200, got ${response.status}`);
  process.exit(1);
}
console.log("PASS: webhook accepted the signed message");
