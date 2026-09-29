const crypto = require("crypto");
const fs = require("fs");
const http = require("http");
const path = require("path");

const PORT = Number(process.env.PORT || 18080);
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, "data");
const LOG_PATH = process.env.LOG_PATH || path.join(DATA_DIR, "server.log");
const DEVICE_SECRET = process.env.INGEST_DEVICE_SECRET || "";
const MAX_BODY_BYTES = 1024 * 1024;

const EVENTS_PATH = path.join(DATA_DIR, "events.jsonl");
const LATEST_PATH = path.join(DATA_DIR, "latest.json");

fs.mkdirSync(DATA_DIR, { recursive: true });

function logLine(message) {
  const line = `[${new Date().toISOString()}] ${message}\n`;
  fs.appendFileSync(LOG_PATH, line, "utf8");
  console.log(message);
}

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload, null, 2);
  res.writeHead(statusCode, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body),
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let total = 0;

    req.on("data", (chunk) => {
      total += chunk.length;
      if (total > MAX_BODY_BYTES) {
        reject(new Error("request body too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });

    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function verifySignature(req, rawBody) {
  if (!DEVICE_SECRET) {
    return { ok: true };
  }

  const timestamp = req.headers["x-timestamp"];
  const signature = req.headers["x-signature"];
  if (!timestamp || !signature) {
    return { ok: false, error: "missing signature headers" };
  }

  const expected = crypto
    .createHmac("sha256", DEVICE_SECRET)
    .update(`${timestamp}.${rawBody}`)
    .digest("hex");

  const expectedBuffer = Buffer.from(expected, "hex");
  const actualBuffer = Buffer.from(String(signature), "hex");
  if (
    expectedBuffer.length !== actualBuffer.length ||
    !crypto.timingSafeEqual(expectedBuffer, actualBuffer)
  ) {
    return { ok: false, error: "invalid signature" };
  }

  return { ok: true };
}

function normalizePayload(payload) {
  if (Array.isArray(payload.events)) {
    return {
      deviceId:
        payload.device_id ||
        payload.deviceId ||
        firstDeviceId(payload.events) ||
        null,
      events: payload.events,
    };
  }

  if (payload.event_type && payload.schema_version) {
    return {
      deviceId: firstDeviceId([payload]),
      events: [payload],
    };
  }

  return { deviceId: null, events: [] };
}

function firstDeviceId(events) {
  for (const event of events) {
    const deviceSerial = event && event.account && event.account.device_serial;
    if (deviceSerial) {
      return deviceSerial;
    }
  }
  return null;
}

function validateEvent(event) {
  const required = ["event_type", "schema_version", "event_id", "captured_at", "source"];
  for (const field of required) {
    if (!event[field]) {
      return `missing event.${field}`;
    }
  }
  if (!event.account || typeof event.account !== "object") {
    return "missing event.account";
  }
  if (!event.metrics || typeof event.metrics !== "object") {
    return "missing event.metrics";
  }
  return null;
}

function shopKeyFor(event, fallbackDeviceId) {
  const account = event.account || {};
  return (
    account.shop_id ||
    account.account_alias ||
    account.virtual_user_id ||
    account.device_serial ||
    fallbackDeviceId ||
    "unknown"
  );
}

function readLatest() {
  try {
    return JSON.parse(fs.readFileSync(LATEST_PATH, "utf8"));
  } catch (_error) {
    return {};
  }
}

function readRecent(limit) {
  let raw;
  try {
    raw = fs.readFileSync(EVENTS_PATH, "utf8");
  } catch (_error) {
    return [];
  }

  return raw
    .split(/\r?\n/)
    .filter(Boolean)
    .slice(-limit)
    .reverse()
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch (error) {
        return { parse_error: error.message, raw: line };
      }
    });
}

function writeAcceptedEvents(deviceId, events, req) {
  const receivedAt = new Date().toISOString();
  const latest = readLatest();
  const eventIds = [];

  for (const event of events) {
    const shopKey = shopKeyFor(event, deviceId);
    const stored = {
      received_at: receivedAt,
      device_id: deviceId,
      shop_key: shopKey,
      remote_address: req.socket.remoteAddress,
      event,
    };

    fs.appendFileSync(EVENTS_PATH, `${JSON.stringify(stored)}\n`, "utf8");
    latest[`${shopKey}:${event.event_type}`] = stored;
    eventIds.push(event.event_id);
  }

  fs.writeFileSync(LATEST_PATH, JSON.stringify(latest, null, 2), "utf8");
  return eventIds;
}

async function handleIngest(req, res) {
  let rawBody;
  try {
    rawBody = await readBody(req);
  } catch (error) {
    sendJson(res, 413, { ok: false, error: error.message });
    return;
  }

  const signatureResult = verifySignature(req, rawBody);
  if (!signatureResult.ok) {
    sendJson(res, 401, { ok: false, error: signatureResult.error });
    return;
  }

  let payload;
  try {
    payload = JSON.parse(rawBody);
  } catch (_error) {
    sendJson(res, 400, { ok: false, error: "invalid json" });
    return;
  }

  const normalized = normalizePayload(payload);
  if (!normalized.events.length) {
    sendJson(res, 400, { ok: false, error: "no events found" });
    return;
  }

  for (const event of normalized.events) {
    const validationError = validateEvent(event);
    if (validationError) {
      sendJson(res, 400, { ok: false, error: validationError });
      return;
    }
  }

  const eventIds = writeAcceptedEvents(normalized.deviceId, normalized.events, req);
  sendJson(res, 200, {
    ok: true,
    accepted: normalized.events.length,
    event_ids: eventIds,
  });
}

function handleLatest(_req, res) {
  sendJson(res, 200, {
    ok: true,
    latest: readLatest(),
  });
}

function handleRecent(req, res, url) {
  const requestedLimit = Number(url.searchParams.get("limit") || 20);
  const limit = Number.isFinite(requestedLimit)
    ? Math.min(Math.max(Math.trunc(requestedLimit), 1), 200)
    : 20;

  sendJson(res, 200, {
    ok: true,
    limit,
    events: readRecent(limit),
  });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);

  if (req.method === "GET" && url.pathname === "/health") {
    sendJson(res, 200, {
      ok: true,
      service: "idlefish-ingest",
      time: new Date().toISOString(),
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/v1/ingest/events") {
    handleIngest(req, res).catch((error) => {
      sendJson(res, 500, { ok: false, error: error.message });
    });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/v1/events/latest") {
    handleLatest(req, res);
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/v1/events/recent") {
    handleRecent(req, res, url);
    return;
  }

  sendJson(res, 404, { ok: false, error: "not found" });
});

server.listen(PORT, () => {
  logLine(`idlefish ingest server listening on http://127.0.0.1:${PORT}`);
  logLine(`data dir: ${DATA_DIR}`);
});

server.on("error", (error) => {
  logLine(`server error: ${error.stack || error.message}`);
  process.exitCode = 1;
});
