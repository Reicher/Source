import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';

function columns(database, table) {
  return database.prepare(`PRAGMA table_info(${table})`).all().map((column) => column.name);
}

export class HubDatabase {
  constructor(databasePath) {
    if (databasePath !== ':memory:') fs.mkdirSync(path.dirname(databasePath), { recursive: true, mode: 0o700 });
    this.database = new DatabaseSync(databasePath);
    if (databasePath !== ':memory:') fs.chmodSync(databasePath, 0o600);
    this.database.exec(`
      PRAGMA foreign_keys = ON;
      PRAGMA journal_mode = WAL;
      PRAGMA synchronous = FULL;
      PRAGMA busy_timeout = 5000;
    `);
    this.removedLegacyUserIds = [];

    // The prototype stored server-side user passwords. Those accounts are
    // deliberately discarded: new Source users only exist after key pairing.
    if (columns(this.database, 'users').includes('password_hash')) {
      this.removedLegacyUserIds = this.database.prepare('SELECT id FROM users').all().map((user) => user.id);
      this.database.exec(`
        DROP TABLE IF EXISTS snapshots;
        DROP TABLE IF EXISTS sessions;
        DROP TABLE IF EXISTS users;
      `);
    }

    this.database.exec(`
      CREATE TABLE IF NOT EXISTS node_state (
        singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
        display_name TEXT NOT NULL,
        node_id TEXT NOT NULL UNIQUE,
        public_key TEXT NOT NULL,
        private_key TEXT NOT NULL,
        admin_password_hash TEXT NOT NULL,
        created_at INTEGER NOT NULL
      ) STRICT;

      CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        display_name TEXT NOT NULL,
        storage_namespace TEXT NOT NULL UNIQUE,
        quota_bytes INTEGER NOT NULL CHECK (quota_bytes > 0),
        created_at INTEGER NOT NULL,
        disabled_at INTEGER
      ) STRICT;

      CREATE TABLE IF NOT EXISTS clients (
        id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        display_name TEXT NOT NULL,
        public_key TEXT NOT NULL UNIQUE,
        credential_hash TEXT NOT NULL UNIQUE,
        protocol_version INTEGER NOT NULL,
        paired_at INTEGER NOT NULL,
        last_seen_at INTEGER,
        revoked_at INTEGER
      ) STRICT;

      CREATE INDEX IF NOT EXISTS clients_user_idx ON clients(user_id);
      CREATE INDEX IF NOT EXISTS clients_credential_idx ON clients(credential_hash);

      CREATE TABLE IF NOT EXISTS snapshots (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        app_id TEXT NOT NULL,
        snapshot_id TEXT NOT NULL,
        byte_count INTEGER NOT NULL,
        sha256 TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        PRIMARY KEY (user_id, app_id, snapshot_id)
      ) STRICT;

      CREATE INDEX IF NOT EXISTS snapshots_latest_idx
        ON snapshots(user_id, app_id, created_at DESC);
    `);
  }

  close() {
    this.database.close();
  }

  isInitialized() {
    return Boolean(this.database.prepare('SELECT 1 FROM node_state WHERE singleton = 1').get());
  }

  initializeNode({ displayName, identity, adminPasswordHash, now }) {
    this.database.exec('BEGIN IMMEDIATE');
    try {
      if (this.isInitialized()) throw Object.assign(new Error('Node is already initialized'), { code: 'already_initialized' });
      this.database.prepare(`
        INSERT INTO node_state (
          singleton, display_name, node_id, public_key, private_key,
          admin_password_hash, created_at
        ) VALUES (1, ?, ?, ?, ?, ?, ?)
      `).run(displayName, identity.nodeId, identity.publicKey, identity.privateKey, adminPasswordHash, now);
      this.database.exec('COMMIT');
    } catch (error) {
      this.database.exec('ROLLBACK');
      throw error;
    }
    return this.getNodeState();
  }

  getNodeState({ includeSecrets = false } = {}) {
    const row = this.database.prepare(`
      SELECT display_name AS displayName, node_id AS nodeId, public_key AS publicKey,
             private_key AS privateKey, admin_password_hash AS adminPasswordHash,
             created_at AS createdAt
      FROM node_state WHERE singleton = 1
    `).get();
    if (!row || includeSecrets) return row;
    const { privateKey, adminPasswordHash, ...publicState } = row;
    return publicState;
  }

  listUsers() {
    return this.database.prepare(`
      SELECT u.id, u.display_name AS displayName, u.storage_namespace AS storageNamespace,
             u.quota_bytes AS quotaBytes, u.created_at AS createdAt, u.disabled_at AS disabledAt,
             COALESCE((SELECT SUM(s.byte_count) FROM snapshots s WHERE s.user_id = u.id), 0) AS storageUsedBytes,
             (SELECT COUNT(*) FROM clients c WHERE c.user_id = u.id AND c.revoked_at IS NULL) AS clientCount
      FROM users u ORDER BY u.created_at, u.id
    `).all();
  }

  findUserById(id) {
    return this.database.prepare(`
      SELECT id, display_name AS displayName, storage_namespace AS storageNamespace,
             quota_bytes AS quotaBytes, created_at AS createdAt, disabled_at AS disabledAt
      FROM users WHERE id = ?
    `).get(id);
  }

  findClientById(id) {
    return this.database.prepare(`
      SELECT c.id, c.user_id AS userId, c.display_name AS clientDisplayName,
             c.public_key AS publicKey, c.paired_at AS pairedAt,
             c.last_seen_at AS lastSeenAt, c.revoked_at AS revokedAt,
             u.display_name AS userDisplayName, u.disabled_at AS disabledAt
      FROM clients c JOIN users u ON u.id = c.user_id WHERE c.id = ?
    `).get(id);
  }

  findClientByCredentialHash(credentialHash) {
    return this.database.prepare(`
      SELECT c.id, c.user_id AS userId, c.display_name AS clientDisplayName,
             c.paired_at AS pairedAt, c.revoked_at AS revokedAt,
             u.display_name AS userDisplayName, u.disabled_at AS disabledAt
      FROM clients c JOIN users u ON u.id = c.user_id WHERE c.credential_hash = ?
    `).get(credentialHash);
  }

  createPairedUser({ displayName, quotaBytes, client, now }) {
    const userId = randomUUID();
    const storageNamespace = randomUUID();
    this.database.exec('BEGIN IMMEDIATE');
    try {
      this.database.prepare(`
        INSERT INTO users (id, display_name, storage_namespace, quota_bytes, created_at)
        VALUES (?, ?, ?, ?, ?)
      `).run(userId, displayName, storageNamespace, quotaBytes, now);
      this.database.prepare(`
        INSERT INTO clients (
          id, user_id, display_name, public_key, credential_hash,
          protocol_version, paired_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
      `).run(client.id, userId, client.displayName, client.publicKey, client.credentialHash, client.protocolVersion, now);
      this.database.exec('COMMIT');
    } catch (error) {
      this.database.exec('ROLLBACK');
      throw error;
    }
    return { user: this.findUserById(userId), client: this.findClientById(client.id) };
  }

  touchClient(id, now) {
    this.database.prepare('UPDATE clients SET last_seen_at = ? WHERE id = ?').run(now, id);
  }

  upsertSnapshot(snapshot) {
    this.database.prepare(`
      INSERT INTO snapshots (user_id, app_id, snapshot_id, byte_count, sha256, created_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(user_id, app_id, snapshot_id) DO UPDATE SET
        byte_count = excluded.byte_count, sha256 = excluded.sha256, created_at = excluded.created_at
    `).run(snapshot.userId, snapshot.appId, snapshot.snapshotId, snapshot.byteCount, snapshot.sha256, snapshot.createdAt);
  }

  listSnapshots({ userId, appId }) {
    return this.database.prepare(`
      SELECT snapshot_id AS id, byte_count AS bytes, sha256, created_at AS createdAt
      FROM snapshots WHERE user_id = ? AND app_id = ? ORDER BY created_at DESC, snapshot_id DESC
    `).all(userId, appId);
  }

  findSnapshot({ userId, appId, snapshotId }) {
    return this.database.prepare(`
      SELECT snapshot_id AS id, byte_count AS bytes, sha256, created_at AS createdAt
      FROM snapshots WHERE user_id = ? AND app_id = ? AND snapshot_id = ?
    `).get(userId, appId, snapshotId);
  }

  latestSnapshot({ userId, appId }) {
    return this.database.prepare(`
      SELECT snapshot_id AS id, byte_count AS bytes, sha256, created_at AS createdAt
      FROM snapshots WHERE user_id = ? AND app_id = ? ORDER BY created_at DESC, snapshot_id DESC LIMIT 1
    `).get(userId, appId);
  }

  totalSnapshotBytes(userId) {
    return this.database.prepare('SELECT COALESCE(SUM(byte_count), 0) AS bytes FROM snapshots WHERE user_id = ?').get(userId).bytes;
  }

  deleteSnapshot({ userId, appId, snapshotId }) {
    this.database.prepare('DELETE FROM snapshots WHERE user_id = ? AND app_id = ? AND snapshot_id = ?').run(userId, appId, snapshotId);
  }
}
