import fs from "node:fs";
import path from "node:path";

const SESSION_PATTERN = /^cs_(?:test|live)_[A-Za-z0-9]+$/;
const STRIPE_VERSION = "2026-04-22.dahlia";

export function loadLocalEnv(root) {
  const file = path.join(root, ".env");
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, "utf8").split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#") || !trimmed.includes("=")) continue;
    const index = trimmed.indexOf("=");
    const key = trimmed.slice(0, index).trim();
    let value = trimmed.slice(index + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    if (key && process.env[key] == null) process.env[key] = value;
  }
}

function formBody(fields) {
  return Object.entries(fields)
    .map(([key, value]) => {
      const encoded = encodeURIComponent(String(value)).replace(/%7B/gi, "{").replace(/%7D/gi, "}");
      return `${encodeURIComponent(key)}=${encoded}`;
    })
    .join("&");
}

async function stripe(pathname, fields) {
  const key = process.env.STRIPE_SECRET_KEY;
  const price = process.env.STRIPE_DONATION_PRICE_ID;
  if (!key || !price) {
    const error = new Error("Donation checkout is not configured.");
    error.status = 503;
    throw error;
  }
  const response = await fetch(`https://api.stripe.com/v1/${pathname}`, {
    method: fields ? "POST" : "GET",
    headers: {
      Authorization: `Bearer ${key}`,
      "Stripe-Version": STRIPE_VERSION,
      ...(fields ? { "Content-Type": "application/x-www-form-urlencoded" } : {}),
    },
    body: fields ? formBody(fields) : undefined,
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(payload.error?.message || "Stripe could not start the donation.");
    error.status = response.status === 401 || response.status === 403 ? 503 : 502;
    throw error;
  }
  return payload;
}

export async function createDonationCheckout(origin, pendingId) {
  const price = process.env.STRIPE_DONATION_PRICE_ID;
  return stripe("checkout/sessions", {
    mode: "payment",
    "managed_payments[enabled]": "true",
    submit_type: "donate",
    "line_items[0][price]": price,
    "line_items[0][quantity]": "1",
    success_url: `${origin}/?donation=success&session_id={CHECKOUT_SESSION_ID}`,
    cancel_url: `${origin}/?donation=cancel`,
    client_reference_id: pendingId,
    "metadata[pending]": pendingId,
  });
}

export async function readDonationCheckout(sessionId) {
  if (!SESSION_PATTERN.test(sessionId || "")) {
    const error = new Error("The donation session was not found.");
    error.status = 404;
    throw error;
  }
  return stripe(`checkout/sessions/${sessionId}`);
}
