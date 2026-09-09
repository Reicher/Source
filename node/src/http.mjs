import { createHash } from 'node:crypto';
import { URL } from 'node:url';
import { AuthService } from './auth.mjs';
import { proveNodeIdentity } from './node-identity.mjs';
import { RateLimiter } from './rate-limit.mjs';
import { SnapshotStorage } from './storage.mjs';
import { tokenHash } from './security.mjs';

class HttpError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function json(response, status, body, headers = {}) {
  const payload = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {
    'cache-control': 'no-store',
    'content-type': 'application/json; charset=utf-8',
    'content-length': payload.length,
    ...headers,
  });
  response.end(payload);
}

function empty(response, status) {
  response.writeHead(status, { 'cache-control': 'no-store' });
  response.end();
}

function errorResponse(response, error) {
  const status = Number.isInteger(error.status) ? error.status : 500;
  const code = typeof error.code === 'string' ? error.code : 'internal_error';
  const message = status >= 500 ? 'Servern kunde inte slutföra begäran.' : error.message;
  json(response, status, { error: { code, message } });
}

function bearerToken(request) {
  const value = request.headers.authorization;
  if (typeof value !== 'string' || !value.startsWith('Bearer ')) return null;
  const token = value.slice(7);
  return token.length >= 32 && token.length <= 256 ? token : null;
}

async function readBody(request, maximumBytes) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > maximumBytes) {
      throw new HttpError(413, 'request_too_large', 'Begäran är för stor.');
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks, length);
}

async function readJson(request, maximumBytes = 64 * 1024) {
  if (!request.headers['content-type']?.toLowerCase().startsWith('application/json')) {
    throw new HttpError(415, 'unsupported_media_type', 'Content-Type måste vara application/json.');
  }
  const body = await readBody(request, maximumBytes);
  try {
    return JSON.parse(body.toString('utf8'));
  } catch {
    throw new HttpError(400, 'invalid_json', 'Begäran innehåller ogiltig JSON.');
  }
}

function validateMessages(body) {
  if (!body || !Array.isArray(body.messages) || body.messages.length < 1 || body.messages.length > 20) {
    throw new HttpError(400, 'invalid_messages', 'Skicka mellan 1 och 20 meddelanden.');
  }
  let total = 0;
  const messages = body.messages.map((message) => {
    if (!message || !['user', 'assistant'].includes(message.role)) {
      throw new HttpError(400, 'invalid_message_role', 'Endast user och assistant är tillåtna roller.');
    }
    if (typeof message.content !== 'string') {
      throw new HttpError(400, 'invalid_message', 'Varje meddelande måste innehålla text.');
    }
    const content = message.content.trim();
    if (content.length < 1 || content.length > 4_000) {
      throw new HttpError(400, 'invalid_message', 'Varje meddelande måste vara 1–4000 tecken.');
    }
    total += content.length;
    return { role: message.role, content };
  });
  if (total > 16_000 || messages.at(-1).role !== 'user') {
    throw new HttpError(400, 'invalid_messages', 'Chatthistoriken är för stor eller slutar inte med en fråga.');
  }
  return messages;
}

function snapshotHeaders(metadata) {
  return {
    'content-type': 'application/octet-stream',
    'content-length': metadata.bytes,
    'x-snapshot-id': metadata.id,
    'x-snapshot-created-at': new Date(metadata.createdAt).toISOString(),
    'x-content-sha256': metadata.sha256,
  };
}

function authorizeStorageApp(config, appId) {
  if (!config.allowedStorageApps.has(appId)) {
    throw new HttpError(403, 'app_not_allowed', 'Appen har inte tillgång till lagringen.');
  }
  return appId;
}

function recoveryMaterial(body) {
  if (
    typeof body?.recoveryKey !== 'string'
    || !/^[A-Za-z0-9_-]{43}$/.test(body.recoveryKey)
    || typeof body?.recoveryEnvelope !== 'string'
    || !/^[A-Za-z0-9_-]{80}$/.test(body.recoveryEnvelope)
  ) {
    throw new HttpError(400, 'invalid_recovery_material', 'Återställningsmaterialet är ogiltigt.');
  }
  return body;
}

export function createRequestHandler({ database, config, ollama, pairing, logger = console }) {
  const auth = new AuthService(database, config);
  const storage = new SnapshotStorage(database, config);
  const chatLimiter = new RateLimiter({ limit: 10, windowMs: 60_000, clock: config.clock });

  return async function handle(request, response) {
    const startedAt = config.clock();
    let status = 500;
    try {
      const url = new URL(request.url, 'http://source-node');
      if (url.search !== '') throw new HttpError(400, 'query_not_supported', 'Query-parametrar stöds inte.');
      const route = `${request.method} ${url.pathname}`;

      if (route === 'GET /healthz') {
        status = 200;
        json(response, status, { status: 'ok' });
        return;
      }

      if (route === 'GET /api/v1/status') {
        status = 200;
        json(response, status, {
          service: 'source-node',
          apiVersion: 1,
          llmAvailable: await ollama.status(),
        });
        return;
      }

      if (route === 'POST /api/v1/pairing/start') {
        status = 200;
        json(response, status, pairing.start(await readJson(request)));
        return;
      }

      if (route === 'POST /api/v1/pairing/complete') {
        status = 201;
        json(response, status, pairing.complete(await readJson(request)));
        return;
      }

      const session = auth.authenticate(bearerToken(request));
      if (!session) throw new HttpError(401, 'authentication_required', 'Giltig inloggning krävs.');

      if (route === 'GET /api/v1/me') {
        status = 200;
        json(response, status, { user: session.user, clientId: session.clientId });
        return;
      }

      if (route === 'POST /api/v1/identity/challenge') {
        status = 200;
        json(response, status, proveNodeIdentity(database, session, await readJson(request)));
        return;
      }

      if (route === 'POST /api/v1/recovery/setup') {
        const body = recoveryMaterial(await readJson(request));
        if (!database.configureRecovery({
          userId: session.user.id,
          recoveryKeyHash: tokenHash(body.recoveryKey),
          recoveryEnvelope: body.recoveryEnvelope,
        })) {
          throw new HttpError(409, 'recovery_already_configured', 'Återställningsnyckeln är redan konfigurerad.');
        }
        status = 201;
        json(response, status, { recoveryConfigured: true });
        return;
      }

      if (route === 'POST /api/v1/chat') {
        if (!chatLimiter.take(session.user.id)) {
          throw new HttpError(429, 'chat_rate_limited', 'För många AI-frågor. Vänta en stund.');
        }
        const messages = validateMessages(await readJson(request));
        let message;
        try {
          message = await ollama.chat(messages);
        } catch (error) {
          logger.warn?.('local model request failed', { error: error.message });
          throw new HttpError(503, 'model_unavailable', 'Den lokala modellen är inte tillgänglig.');
        }
        status = 200;
        json(response, status, { message });
        return;
      }

      const listMatch = url.pathname.match(/^\/api\/v1\/storage\/([a-z][a-z0-9-]{1,31})\/snapshots$/);
      if (request.method === 'GET' && listMatch) {
        const appId = authorizeStorageApp(config, listMatch[1]);
        status = 200;
        json(response, status, { snapshots: storage.list({ userId: session.user.id, appId }) });
        return;
      }

      const latestMatch = url.pathname.match(/^\/api\/v1\/storage\/([a-z][a-z0-9-]{1,31})\/snapshots\/latest$/);
      if (request.method === 'GET' && latestMatch) {
        const appId = authorizeStorageApp(config, latestMatch[1]);
        const snapshot = await storage.latest({ userId: session.user.id, appId });
        if (!snapshot) throw new HttpError(404, 'snapshot_not_found', 'Ingen backup finns.');
        status = 200;
        response.writeHead(status, { 'cache-control': 'no-store', ...snapshotHeaders(snapshot.metadata) });
        response.end(snapshot.body);
        return;
      }

      const itemMatch = url.pathname.match(
        /^\/api\/v1\/storage\/([a-z][a-z0-9-]{1,31})\/snapshots\/([0-9a-f-]{36})$/i,
      );
      if (itemMatch && request.method === 'PUT') {
        const appId = authorizeStorageApp(config, itemMatch[1]);
        if (!request.headers['content-type']?.toLowerCase().startsWith('application/octet-stream')) {
          throw new HttpError(415, 'unsupported_media_type', 'Snapshoten måste vara application/octet-stream.');
        }
        const body = await readBody(request, config.maximumSnapshotBytes);
        if (body.length < 32) throw new HttpError(400, 'invalid_snapshot', 'Snapshoten är för liten.');
        const declaredHash = request.headers['x-content-sha256'];
        const actualHash = createHash('sha256').update(body).digest('hex');
        if (typeof declaredHash === 'string' && declaredHash.toLowerCase() !== actualHash) {
          throw new HttpError(400, 'snapshot_hash_mismatch', 'Snapshotens checksumma stämmer inte.');
        }
        const metadata = await storage.put({
          userId: session.user.id,
          appId,
          snapshotId: itemMatch[2],
          body,
        });
        status = 201;
        json(response, status, { snapshot: metadata }, { location: url.pathname });
        return;
      }

      if (itemMatch && request.method === 'DELETE') {
        const appId = authorizeStorageApp(config, itemMatch[1]);
        const deleted = await storage.delete({
          userId: session.user.id,
          appId,
          snapshotId: itemMatch[2],
        });
        if (!deleted) throw new HttpError(404, 'snapshot_not_found', 'Backupen finns inte.');
        status = 204;
        empty(response, status);
        return;
      }

      throw new HttpError(404, 'not_found', 'Endpointen finns inte.');
    } catch (error) {
      status = Number.isInteger(error.status) ? error.status : 500;
      if (status >= 500 && error.code !== 'model_unavailable') {
        logger.error?.('source node request failed', { error: error.message });
      }
      if (!response.headersSent) errorResponse(response, error);
      else response.destroy();
    } finally {
      const method = request.method ?? 'UNKNOWN';
      const pathname = request.url?.split('?')[0] ?? '/';
      logger.info?.(`${method} ${pathname} ${status} ${config.clock() - startedAt}ms`);
    }
  };
}
