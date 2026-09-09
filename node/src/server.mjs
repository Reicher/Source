import fs from 'node:fs';
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { loadConfig } from './config.mjs';
import { HubDatabase } from './database.mjs';
import { createRequestHandler } from './http.mjs';
import { OllamaClient } from './ollama.mjs';

export function createHubServer(overrides = {}) {
  const config = loadConfig(overrides);
  fs.mkdirSync(config.storageRoot, { recursive: true, mode: 0o700 });
  const database = overrides.database ?? new HubDatabase(config.databasePath);
  const ollama = overrides.ollama ?? new OllamaClient(config);
  const handler = createRequestHandler({
    database,
    config,
    ollama,
    logger: overrides.logger ?? console,
  });
  const server = http.createServer({
    requestTimeout: Math.max(config.ollamaTimeoutMs + 5_000, 30_000),
    headersTimeout: 10_000,
    keepAliveTimeout: 5_000,
    maxHeaderSize: 16 * 1024,
  }, handler);
  server.on('close', () => database.close());
  return { server, database, config };
}

const isMain = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1];
if (isMain) {
  const { server, config } = createHubServer();
  server.listen(config.port, config.host, () => {
    console.log(`source node listening on ${config.host}:${config.port}`);
  });
}
