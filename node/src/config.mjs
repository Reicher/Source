import path from 'node:path';
import os from 'node:os';

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

function booleanSetting(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  if (raw === '1' || raw === 'true') return true;
  if (raw === '0' || raw === 'false') return false;
  throw new Error(`${name} must be true/false or 1/0`);
}

export function loadConfig(overrides = {}) {
  const stateRoot = overrides.stateRoot ?? process.env.SOURCE_NODE_STATE_ROOT ?? '/state';
  const gatewayHost = overrides.gatewayHost ?? process.env.SOURCE_GATEWAY_HOST ?? '127.0.0.1';
  const httpsPort = overrides.httpsPort ?? positiveInteger('SOURCE_HTTPS_PORT', 8443);
  const adminHost = overrides.adminHost ?? process.env.SOURCE_ADMIN_HOST ?? '127.0.0.1';
  const containerAdmin = overrides.containerAdmin ?? process.env.SOURCE_ADMIN_CONTAINER_MODE === '1';
  if (!['127.0.0.1', '::1', 'localhost'].includes(adminHost) && !containerAdmin) {
    throw new Error('SOURCE_ADMIN_HOST must be loopback (container deployments must explicitly set SOURCE_ADMIN_CONTAINER_MODE=1)');
  }
  return {
    host: overrides.host ?? process.env.SOURCE_NODE_HOST ?? '127.0.0.1',
    port: overrides.port ?? positiveInteger('SOURCE_NODE_PORT', 8080),
    httpsPort,
    discoveryEnabled: overrides.discoveryEnabled ?? booleanSetting('SOURCE_DISCOVERY_ENABLED', false),
    adminHost,
    adminPort: overrides.adminPort ?? positiveInteger('SOURCE_ADMIN_PORT', 9090),
    adminSessionTtlMs:
      overrides.adminSessionTtlMs ?? positiveInteger('ADMIN_SESSION_TTL_SECONDS', 8 * 60 * 60) * 1000,
    pairingInvitationTtlMs:
      overrides.pairingInvitationTtlMs ?? positiveInteger('PAIRING_INVITATION_TTL_SECONDS', 5 * 60) * 1000,
    pairingBaseUrl:
      overrides.pairingBaseUrl
      ?? process.env.SOURCE_PAIRING_BASE_URL
      ?? `https://${gatewayHost}:${httpsPort}/api/v1/pairing`,
    pairingCaCertificatePath:
      overrides.pairingCaCertificatePath
      ?? process.env.SOURCE_PAIRING_CA_CERTIFICATE_PATH
      ?? '/artifacts/source-node-ca.crt',
    suggestedNodeName: overrides.suggestedNodeName ?? os.hostname(),
    databasePath:
      overrides.databasePath ?? process.env.SOURCE_NODE_DATABASE_PATH ?? path.join(stateRoot, 'source-node.sqlite'),
    storageRoot: overrides.storageRoot ?? process.env.SOURCE_NODE_STORAGE_ROOT ?? '/vaults',
    maximumSnapshotBytes:
      overrides.maximumSnapshotBytes ?? positiveInteger('MAX_SNAPSHOT_MIB', 32) * 1024 * 1024,
    snapshotRetention:
      overrides.snapshotRetention ?? positiveInteger('SNAPSHOT_RETENTION_COUNT', 20),
    allowedStorageApps:
      overrides.allowedStorageApps ?? identifierList('ALLOWED_STORAGE_APPS', 'thoughts,source-client'),
    ollamaUrl: overrides.ollamaUrl ?? process.env.OLLAMA_URL ?? 'http://ollama:11434',
    ollamaModel: overrides.ollamaModel ?? process.env.OLLAMA_MODEL ?? 'qwen3:4b',
    ollamaTimeoutMs:
      overrides.ollamaTimeoutMs ?? positiveInteger('OLLAMA_TIMEOUT_SECONDS', 120) * 1000,
    clock: overrides.clock ?? Date.now,
  };
}
