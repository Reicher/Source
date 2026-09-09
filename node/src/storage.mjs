import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash, randomUUID } from 'node:crypto';

const SNAPSHOT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const APP_ID = /^[a-z][a-z0-9-]{1,31}$/;

function assertIdentifier(value, pattern, name) {
  if (typeof value !== 'string' || !pattern.test(value)) {
    throw Object.assign(new Error(`Invalid ${name}`), { status: 400, code: `invalid_${name}` });
  }
}

export class SnapshotStorage {
  constructor(database, config) {
    this.database = database;
    this.root = path.resolve(config.storageRoot);
    this.quota = config.userStorageQuotaBytes;
    this.retention = config.snapshotRetention;
    this.clock = config.clock;
  }

  validate(appId, snapshotId) {
    assertIdentifier(appId, APP_ID, 'app_id');
    assertIdentifier(snapshotId, SNAPSHOT_ID, 'snapshot_id');
  }

  list({ userId, appId }) {
    assertIdentifier(appId, APP_ID, 'app_id');
    return this.database.listSnapshots({ userId, appId });
  }

  async put({ userId, appId, snapshotId, body }) {
    this.validate(appId, snapshotId);
    const existing = this.database.findSnapshot({ userId, appId, snapshotId });
    const used = this.database.totalSnapshotBytes(userId) - (existing?.bytes ?? 0);
    if (used + body.length > this.quota) {
      throw Object.assign(new Error('User storage quota exceeded'), {
        status: 413,
        code: 'storage_quota_exceeded',
      });
    }

    const directory = this.#directory(userId, appId);
    await fs.mkdir(directory, { recursive: true, mode: 0o700 });
    const destination = this.#path(userId, appId, snapshotId);
    const temporary = path.join(directory, `.${snapshotId}.${randomUUID()}.tmp`);
    await fs.writeFile(temporary, body, { flag: 'wx', mode: 0o600 });
    try {
      await fs.rename(temporary, destination);
    } catch (error) {
      await fs.rm(temporary, { force: true });
      throw error;
    }

    const snapshot = {
      userId,
      appId,
      snapshotId,
      byteCount: body.length,
      sha256: createHash('sha256').update(body).digest('hex'),
      createdAt: this.clock(),
    };
    this.database.upsertSnapshot(snapshot);
    await this.#prune(userId, appId);
    return this.database.findSnapshot({ userId, appId, snapshotId });
  }

  async get({ userId, appId, snapshotId }) {
    this.validate(appId, snapshotId);
    const metadata = this.database.findSnapshot({ userId, appId, snapshotId });
    if (!metadata) return null;
    try {
      return { metadata, body: await fs.readFile(this.#path(userId, appId, snapshotId)) };
    } catch (error) {
      if (error.code === 'ENOENT') return null;
      throw error;
    }
  }

  async latest({ userId, appId }) {
    assertIdentifier(appId, APP_ID, 'app_id');
    const latest = this.database.latestSnapshot({ userId, appId });
    if (!latest) return null;
    return this.get({ userId, appId, snapshotId: latest.id });
  }

  async delete({ userId, appId, snapshotId }) {
    this.validate(appId, snapshotId);
    const existing = this.database.findSnapshot({ userId, appId, snapshotId });
    if (!existing) return false;
    await fs.rm(this.#path(userId, appId, snapshotId), { force: true });
    this.database.deleteSnapshot({ userId, appId, snapshotId });
    return true;
  }

  async deleteUser(userId) {
    if (!/^[0-9a-f-]{36}$/i.test(userId)) throw new Error('Invalid user id');
    await fs.rm(path.join(this.root, userId), { recursive: true, force: true });
  }

  #directory(userId, appId) {
    return path.join(this.root, userId, appId, 'snapshots');
  }

  #path(userId, appId, snapshotId) {
    return path.join(this.#directory(userId, appId), `${snapshotId}.bin`);
  }

  async #prune(userId, appId) {
    const snapshots = this.database.listSnapshots({ userId, appId });
    for (const snapshot of snapshots.slice(this.retention)) {
      await this.delete({ userId, appId, snapshotId: snapshot.id });
    }
  }
}
