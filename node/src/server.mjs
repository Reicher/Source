import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createAdminHandler } from './admin-http.mjs';
import { loadConfig } from './config.mjs';
import { HubDatabase } from './database.mjs';
import { createRequestHandler } from './http.mjs';
import { SourceDiscovery } from './discovery.mjs';
import { OllamaClient } from './ollama.mjs';
import { PairingService } from './pairing.mjs';

function httpServer(handler, config) {
  return http.createServer({
    requestTimeout: Math.max(config.ollamaTimeoutMs + 5_000, 30_000),
    headersTimeout: 10_000,
    keepAliveTimeout: 5_000,
    maxHeaderSize: 16 * 1024,
  }, handler);
}

function runtime(overrides = {}) {
  const config = loadConfig(overrides);
  fs.mkdirSync(config.storageRoot, { recursive: true, mode: 0o700 });
  const database = overrides.database ?? new HubDatabase(config.databasePath);
  for (const userId of database.removedLegacyUserIds ?? []) {
    if (/^[0-9a-f-]{36}$/i.test(userId)) {
      fs.rmSync(path.join(path.resolve(config.storageRoot), userId), { recursive: true, force: true });
    }
  }
  const ollama = overrides.ollama ?? new OllamaClient(config);
  const pairing = overrides.pairing ?? new PairingService(database, config);
  const logger = overrides.logger ?? console;
  return { config, database, ollama, pairing, logger };
}

// Kept as the small LAN-server factory used by API integrations and tests.
export function createHubServer(overrides = {}) {
  const state = runtime(overrides);
  const server = httpServer(createRequestHandler(state), state.config);
  server.on('close', () => state.database.close());
  return { server, ...state };
}

export function createSourceNode(overrides = {}) {
  const state = runtime(overrides);
  const server = httpServer(createRequestHandler(state), state.config);
  const adminServer = httpServer(createAdminHandler(state), state.config);
  const discovery = overrides.discovery ?? new SourceDiscovery(state);
  let closed = false;
  async function close() {
    if (closed) return;
    closed = true;
    await discovery.stop();
    await Promise.all([server, adminServer].map((item) => new Promise((resolve, reject) => {
      if (!item.listening) return resolve();
      item.close((error) => error ? reject(error) : resolve());
    })));
    state.database.close();
  }
  return { server, adminServer, discovery, close, ...state };
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isMain) {
  const source = createSourceNode();
  source.server.listen(source.config.port, source.config.host, () => {
    console.log(`source node listening on ${source.config.host}:${source.config.port}`);
  });
  source.adminServer.listen(source.config.adminPort, source.config.adminHost, () => {
    console.log(`source admin listening on ${source.config.adminHost}:${source.config.adminPort}`);
  });
  if (source.config.discoveryEnabled) source.discovery.start();
  const shutdown = () => source.close().finally(() => process.exit(0));
  process.once('SIGINT', shutdown);
  process.once('SIGTERM', shutdown);
}
