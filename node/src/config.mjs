import path from 'node:path';

function positiveInteger(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return value;
}

function identifierList(name, fallback) {
  const raw = process.env[name] ?? fallback;
  const values = raw.split(',').map((value) => value.trim()).filter(Boolean);
  if (values.length < 1 || values.some((value) => !/^[a-z][a-z0-9-]{1,31}$/.test(value))) {
    throw new Error(`${name} must be a comma-separated list of application identifiers`);
  }
  return new Set(values);
}

export function loadConfig(overrides = {}) {
  const stateRoot = overrides.stateRoot ?? process.env.SOURCE_NODE_STATE_ROOT ?? '/state';
  return {
    host: overrides.host ?? process.env.SOURCE_NODE_HOST ?? '0.0.0.0',
    port: overrides.port ?? positiveInteger('SOURCE_NODE_PORT', 8080),
    databasePath:
      overrides.databasePath ?? process.env.SOURCE_NODE_DATABASE_PATH ?? path.join(stateRoot, 'source-node.sqlite'),
    storageRoot: overrides.storageRoot ?? process.env.SOURCE_NODE_STORAGE_ROOT ?? '/vaults',
    accessTokenTtlMs:
      overrides.accessTokenTtlMs ?? positiveInteger('ACCESS_TOKEN_TTL_SECONDS', 15 * 60) * 1000,
    refreshTokenTtlMs:
      overrides.refreshTokenTtlMs ?? positiveInteger('REFRESH_TOKEN_TTL_DAYS', 30) * 86_400_000,
    maximumSnapshotBytes:
      overrides.maximumSnapshotBytes ?? positiveInteger('MAX_SNAPSHOT_MIB', 32) * 1024 * 1024,
    userStorageQuotaBytes:
      overrides.userStorageQuotaBytes ?? positiveInteger('USER_STORAGE_QUOTA_MIB', 1024) * 1024 * 1024,
    snapshotRetention:
      overrides.snapshotRetention ?? positiveInteger('SNAPSHOT_RETENTION_COUNT', 20),
    allowedStorageApps:
      overrides.allowedStorageApps ?? identifierList('ALLOWED_STORAGE_APPS', 'thoughts'),
    ollamaUrl: overrides.ollamaUrl ?? process.env.OLLAMA_URL ?? 'http://ollama:11434',
    ollamaModel: overrides.ollamaModel ?? process.env.OLLAMA_MODEL ?? 'qwen3:4b',
    ollamaTimeoutMs:
      overrides.ollamaTimeoutMs ?? positiveInteger('OLLAMA_TIMEOUT_SECONDS', 120) * 1000,
    clock: overrides.clock ?? Date.now,
  };
}
