import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { randomUUID } from 'node:crypto';
import { createHubServer } from '../src/server.mjs';
import { hashPassword } from '../src/security.mjs';

const quietLogger = { info() {}, warn() {}, error() {} };

async function request(baseUrl, pathname, options = {}) {
  return fetch(`${baseUrl}${pathname}`, options);
}

async function responseJson(response) {
  const body = await response.json();
  assert.ok(body);
  return body;
}

test('Source Node API keeps users isolated and the model behind authentication', async (suite) => {
  const temporaryRoot = await fs.mkdtemp(path.join(os.tmpdir(), 'source-node-test-'));
  let currentTime = 1_800_000_000_000;
  const modelCalls = [];
  const ollama = {
    async status() { return true; },
    async chat(messages) {
      modelCalls.push(messages);
      return { role: 'assistant', content: 'Lokalt svar' };
    },
  };
  const { server, database } = createHubServer({
    databasePath: path.join(temporaryRoot, 'state', 'hub.sqlite'),
    storageRoot: path.join(temporaryRoot, 'vaults'),
    accessTokenTtlMs: 60_000,
    refreshTokenTtlMs: 120_000,
    maximumSnapshotBytes: 1_024,
    userStorageQuotaBytes: 2_048,
    snapshotRetention: 2,
    allowedStorageApps: new Set(['thoughts']),
    clock: () => ++currentTime,
    ollama,
    logger: quietLogger,
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;

  const passwords = { alice: 'alice-password-for-test', bob: 'bob-password-for-test' };
  for (const [username, password] of Object.entries(passwords)) {
    database.createUser({
      username,
      passwordHash: await hashPassword(password),
      now: Date.now(),
    });
  }

  async function login(username) {
    const response = await request(baseUrl, '/api/v1/auth/login', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        username,
        password: passwords[username],
        deviceName: 'Testtelefon',
      }),
    });
    assert.equal(response.status, 200);
    return responseJson(response);
  }

  try {
    await suite.test('status is minimal and does not require authentication', async () => {
      const response = await request(baseUrl, '/api/v1/status');
      assert.equal(response.status, 200);
      assert.deepEqual(await responseJson(response), {
        service: 'source-node',
        apiVersion: 1,
        llmAvailable: true,
      });
    });

    const alice = await login('alice');
    const bob = await login('bob');

    await suite.test('invalid credentials are rejected without account disclosure', async () => {
      const response = await request(baseUrl, '/api/v1/auth/login', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ username: 'alice', password: 'incorrect-password', deviceName: 'Telefon' }),
      });
      assert.equal(response.status, 401);
      assert.equal((await responseJson(response)).error.code, 'invalid_credentials');
    });

    await suite.test('opaque snapshots are isolated by the authenticated user', async () => {
      const snapshotId = randomUUID();
      const ciphertext = Buffer.alloc(96, 0xa7);
      const upload = await request(
        baseUrl,
        `/api/v1/storage/thoughts/snapshots/${snapshotId}`,
        {
          method: 'PUT',
          headers: {
            authorization: `Bearer ${alice.accessToken}`,
            'content-type': 'application/octet-stream',
          },
          body: ciphertext,
        },
      );
      assert.equal(upload.status, 201);

      const aliceList = await request(baseUrl, '/api/v1/storage/thoughts/snapshots', {
        headers: { authorization: `Bearer ${alice.accessToken}` },
      });
      assert.equal((await responseJson(aliceList)).snapshots.length, 1);

      const bobList = await request(baseUrl, '/api/v1/storage/thoughts/snapshots', {
        headers: { authorization: `Bearer ${bob.accessToken}` },
      });
      assert.deepEqual((await responseJson(bobList)).snapshots, []);

      const download = await request(baseUrl, '/api/v1/storage/thoughts/snapshots/latest', {
        headers: { authorization: `Bearer ${alice.accessToken}` },
      });
      assert.equal(download.status, 200);
      assert.deepEqual(Buffer.from(await download.arrayBuffer()), ciphertext);

      const bobDownload = await request(baseUrl, '/api/v1/storage/thoughts/snapshots/latest', {
        headers: { authorization: `Bearer ${bob.accessToken}` },
      });
      assert.equal(bobDownload.status, 404);

      for (let index = 0; index < 2; index += 1) {
        const response = await request(
          baseUrl,
          `/api/v1/storage/thoughts/snapshots/${randomUUID()}`,
          {
            method: 'PUT',
            headers: {
              authorization: `Bearer ${alice.accessToken}`,
              'content-type': 'application/octet-stream',
            },
            body: Buffer.alloc(96, index + 1),
          },
        );
        assert.equal(response.status, 201);
      }
      const retained = await request(baseUrl, '/api/v1/storage/thoughts/snapshots', {
        headers: { authorization: `Bearer ${alice.accessToken}` },
      });
      assert.equal((await responseJson(retained)).snapshots.length, 2);
    });

    await suite.test('storage app identifiers are allowlisted', async () => {
      const response = await request(baseUrl, '/api/v1/storage/unknown/snapshots', {
        headers: { authorization: `Bearer ${alice.accessToken}` },
      });
      assert.equal(response.status, 403);
      assert.equal((await responseJson(response)).error.code, 'app_not_allowed');
    });

    await suite.test('snapshot size and checksum are enforced before storage', async () => {
      const mismatch = await request(
        baseUrl,
        `/api/v1/storage/thoughts/snapshots/${randomUUID()}`,
        {
          method: 'PUT',
          headers: {
            authorization: `Bearer ${alice.accessToken}`,
            'content-type': 'application/octet-stream',
            'x-content-sha256': '0'.repeat(64),
          },
          body: Buffer.alloc(64, 4),
        },
      );
      assert.equal(mismatch.status, 400);
      assert.equal((await responseJson(mismatch)).error.code, 'snapshot_hash_mismatch');

      const tooLarge = await request(
        baseUrl,
        `/api/v1/storage/thoughts/snapshots/${randomUUID()}`,
        {
          method: 'PUT',
          headers: {
            authorization: `Bearer ${alice.accessToken}`,
            'content-type': 'application/octet-stream',
          },
          body: Buffer.alloc(1_025, 5),
        },
      );
      assert.equal(tooLarge.status, 413);
    });

    await suite.test('chat accepts only explicit messages and requires authentication', async () => {
      const unauthenticated = await request(baseUrl, '/api/v1/chat', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ messages: [{ role: 'user', content: 'Hej' }] }),
      });
      assert.equal(unauthenticated.status, 401);

      const response = await request(baseUrl, '/api/v1/chat', {
        method: 'POST',
        headers: {
          authorization: `Bearer ${alice.accessToken}`,
          'content-type': 'application/json',
        },
        body: JSON.stringify({ messages: [{ role: 'user', content: 'Hej lokala modell' }] }),
      });
      assert.equal(response.status, 200);
      assert.deepEqual((await responseJson(response)).message, {
        role: 'assistant',
        content: 'Lokalt svar',
      });
      assert.deepEqual(modelCalls, [[{ role: 'user', content: 'Hej lokala modell' }]]);
    });

    await suite.test('refresh rotates credentials and logout revokes the access token', async () => {
      const refreshedResponse = await request(baseUrl, '/api/v1/auth/refresh', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ refreshToken: alice.refreshToken }),
      });
      assert.equal(refreshedResponse.status, 200);
      const refreshed = await responseJson(refreshedResponse);
      assert.notEqual(refreshed.accessToken, alice.accessToken);

      const oldAccess = await request(baseUrl, '/api/v1/me', {
        headers: { authorization: `Bearer ${alice.accessToken}` },
      });
      assert.equal(oldAccess.status, 401);

      const logout = await request(baseUrl, '/api/v1/auth/logout', {
        method: 'POST',
        headers: { authorization: `Bearer ${refreshed.accessToken}` },
      });
      assert.equal(logout.status, 204);

      const revoked = await request(baseUrl, '/api/v1/me', {
        headers: { authorization: `Bearer ${refreshed.accessToken}` },
      });
      assert.equal(revoked.status, 401);
    });
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await fs.rm(temporaryRoot, { recursive: true, force: true });
  }
});
