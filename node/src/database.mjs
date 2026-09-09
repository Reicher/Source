import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { DatabaseSync } from 'node:sqlite';

export class HubDatabase {
  constructor(databasePath) {
    if (databasePath !== ':memory:') {
      fs.mkdirSync(path.dirname(databasePath), { recursive: true, mode: 0o700 });
    }
    this.database = new DatabaseSync(databasePath);
    this.database.exec(`
      PRAGMA foreign_keys = ON;
      PRAGMA journal_mode = WAL;
      PRAGMA synchronous = FULL;
      PRAGMA busy_timeout = 5000;

      CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        username TEXT NOT NULL UNIQUE COLLATE NOCASE,
        password_hash TEXT NOT NULL,
        created_at INTEGER NOT NULL,
        disabled_at INTEGER
      ) STRICT;

      CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        device_name TEXT NOT NULL,
        access_hash TEXT NOT NULL UNIQUE,
        access_expires_at INTEGER NOT NULL,
        refresh_hash TEXT NOT NULL UNIQUE,
        refresh_expires_at INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        revoked_at INTEGER
      ) STRICT;

      CREATE INDEX IF NOT EXISTS sessions_access_idx ON sessions(access_hash);
      CREATE INDEX IF NOT EXISTS sessions_refresh_idx ON sessions(refresh_hash);

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

  createUser({ username, passwordHash, now }) {
    const id = randomUUID();
    this.database.prepare(`
      INSERT INTO users (id, username, password_hash, created_at)
      VALUES (?, ?, ?, ?)
    `).run(id, username, passwordHash, now);
    return { id, username, createdAt: now };
  }

  listUsers() {
    return this.database.prepare(`
      SELECT id, username, created_at AS createdAt, disabled_at AS disabledAt
      FROM users ORDER BY username
    `).all();
  }

  findUserByUsername(username) {
    return this.database.prepare(`
      SELECT id, username, password_hash AS passwordHash, created_at AS createdAt,
             disabled_at AS disabledAt
      FROM users WHERE username = ? COLLATE NOCASE
    `).get(username);
  }

  findUserById(id) {
    return this.database.prepare(`
      SELECT id, username, created_at AS createdAt, disabled_at AS disabledAt
      FROM users WHERE id = ?
    `).get(id);
  }

  updatePassword({ userId, passwordHash, now }) {
    this.database.exec('BEGIN IMMEDIATE');
    try {
      this.database.prepare('UPDATE users SET password_hash = ? WHERE id = ?')
        .run(passwordHash, userId);
      this.database.prepare('UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL')
        .run(now, userId);
      this.database.exec('COMMIT');
    } catch (error) {
      this.database.exec('ROLLBACK');
      throw error;
    }
  }

  disableUser({ userId, now }) {
    this.database.exec('BEGIN IMMEDIATE');
    try {
      this.database.prepare('UPDATE users SET disabled_at = ? WHERE id = ?').run(now, userId);
      this.database.prepare('UPDATE sessions SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL')
        .run(now, userId);
      this.database.exec('COMMIT');
    } catch (error) {
      this.database.exec('ROLLBACK');
      throw error;
    }
  }

  enableUser(userId) {
    this.database.prepare('UPDATE users SET disabled_at = NULL WHERE id = ?').run(userId);
  }

  deleteUser(userId) {
    this.database.prepare('DELETE FROM users WHERE id = ?').run(userId);
  }

  createSession(session) {
    this.database.prepare(`
      INSERT INTO sessions (
        id, user_id, device_name, access_hash, access_expires_at,
        refresh_hash, refresh_expires_at, created_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      session.id,
      session.userId,
      session.deviceName,
      session.accessHash,
      session.accessExpiresAt,
      session.refreshHash,
      session.refreshExpiresAt,
      session.createdAt,
    );
  }

  findSessionByAccessHash(accessHash) {
    return this.database.prepare(`
      SELECT s.id, s.user_id AS userId, s.access_expires_at AS accessExpiresAt,
             s.refresh_expires_at AS refreshExpiresAt, s.revoked_at AS revokedAt,
             u.username, u.disabled_at AS disabledAt
      FROM sessions s JOIN users u ON u.id = s.user_id
      WHERE s.access_hash = ?
    `).get(accessHash);
  }

  findSessionByRefreshHash(refreshHash) {
    return this.database.prepare(`
      SELECT s.id, s.user_id AS userId, s.refresh_expires_at AS refreshExpiresAt,
             s.revoked_at AS revokedAt, u.username, u.disabled_at AS disabledAt
      FROM sessions s JOIN users u ON u.id = s.user_id
      WHERE s.refresh_hash = ?
    `).get(refreshHash);
  }

  rotateSession({ id, accessHash, accessExpiresAt, refreshHash, refreshExpiresAt }) {
    this.database.prepare(`
      UPDATE sessions
      SET access_hash = ?, access_expires_at = ?, refresh_hash = ?, refresh_expires_at = ?
      WHERE id = ? AND revoked_at IS NULL
    `).run(accessHash, accessExpiresAt, refreshHash, refreshExpiresAt, id);
  }

  revokeSession({ id, now }) {
    this.database.prepare(`
      UPDATE sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL
    `).run(now, id);
  }

  listSessions(userId) {
    return this.database.prepare(`
      SELECT id, device_name AS deviceName, created_at AS createdAt,
             access_expires_at AS accessExpiresAt, refresh_expires_at AS refreshExpiresAt,
             revoked_at AS revokedAt
      FROM sessions WHERE user_id = ? ORDER BY created_at DESC
    `).all(userId);
  }

  findSessionById(id) {
    return this.database.prepare(`
      SELECT id, user_id AS userId, device_name AS deviceName, revoked_at AS revokedAt
      FROM sessions WHERE id = ?
    `).get(id);
  }

  deleteExpiredSessions(now) {
    this.database.prepare('DELETE FROM sessions WHERE refresh_expires_at <= ? OR revoked_at IS NOT NULL')
      .run(now);
  }

  upsertSnapshot(snapshot) {
    this.database.prepare(`
      INSERT INTO snapshots (user_id, app_id, snapshot_id, byte_count, sha256, created_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(user_id, app_id, snapshot_id) DO UPDATE SET
        byte_count = excluded.byte_count,
        sha256 = excluded.sha256,
        created_at = excluded.created_at
    `).run(
      snapshot.userId,
      snapshot.appId,
      snapshot.snapshotId,
      snapshot.byteCount,
      snapshot.sha256,
      snapshot.createdAt,
    );
  }

  listSnapshots({ userId, appId }) {
    return this.database.prepare(`
      SELECT snapshot_id AS id, byte_count AS bytes, sha256, created_at AS createdAt
      FROM snapshots
      WHERE user_id = ? AND app_id = ?
      ORDER BY created_at DESC, snapshot_id DESC
    `).all(userId, appId);
  }

  findSnapshot({ userId, appId, snapshotId }) {
    return this.database.prepare(`
      SELECT snapshot_id AS id, byte_count AS bytes, sha256, created_at AS createdAt
      FROM snapshots
      WHERE user_id = ? AND app_id = ? AND snapshot_id = ?
    `).get(userId, appId, snapshotId);
  }

  latestSnapshot({ userId, appId }) {
    return this.database.prepare(`
      SELECT snapshot_id AS id, byte_count AS bytes, sha256, created_at AS createdAt
      FROM snapshots
      WHERE user_id = ? AND app_id = ?
      ORDER BY created_at DESC, snapshot_id DESC LIMIT 1
    `).get(userId, appId);
  }

  totalSnapshotBytes(userId) {
    return this.database.prepare(`
      SELECT COALESCE(SUM(byte_count), 0) AS bytes FROM snapshots WHERE user_id = ?
    `).get(userId).bytes;
  }

  deleteSnapshot({ userId, appId, snapshotId }) {
    this.database.prepare(`
      DELETE FROM snapshots WHERE user_id = ? AND app_id = ? AND snapshot_id = ?
    `).run(userId, appId, snapshotId);
  }
}
