import assert from 'node:assert/strict';
import { createPublicKey, generateKeyPairSync, randomBytes, randomUUID, sign, verify } from 'node:crypto';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { loadConfig } from '../src/config.mjs';
import { createSourceNode } from '../src/server.mjs';

const quietLogger = { info() {}, warn() {}, error() {} };
const adminPassword = 'correct horse source battery';
const pairingCaCertificatePath = new URL('./fixtures/source-test-ca.crt', import.meta.url);

async function listen(source) {
  await Promise.all([
    new Promise((resolve) => source.server.listen(0, '127.0.0.1', resolve)),
    new Promise((resolve) => source.adminServer.listen(0, '127.0.0.1', resolve)),
  ]);
  return {
    api: `http://127.0.0.1:${source.server.address().port}`,
    admin: `http://127.0.0.1:${source.adminServer.address().port}`,
  };
}

async function json(response) {
  const body = await response.json();
  assert.ok(body);
  return body;
}

async function initialize(admin) {
  return fetch(`${admin}/admin/api/initialize`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', origin: admin },
    body: JSON.stringify({
      displayName: 'Source hemma',
      password: adminPassword,
      passwordConfirmation: adminPassword,
    }),
  });
}

async function login(admin) {
  const response = await fetch(`${admin}/admin/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', origin: admin },
    body: JSON.stringify({ password: adminPassword }),
  });
  assert.equal(response.status, 200);
  const body = await json(response);
  return {
    cookie: response.headers.get('set-cookie').split(';')[0],
    csrf: body.csrfToken,
  };
}

function adminHeaders(session, admin, jsonBody = false) {
  return {
    cookie: session.cookie,
    origin: admin,
    'x-source-csrf': session.csrf,
    ...(jsonBody ? { 'content-type': 'application/json' } : {}),
  };
}

async function invite(admin, session, quotaBytes = 5 * 1024 ** 3) {
  const response = await fetch(`${admin}/admin/api/pairing-invitations`, {
    method: 'POST',
    headers: adminHeaders(session, admin, true),
    body: JSON.stringify({ quotaBytes }),
  });
  assert.equal(response.status, 201);
  return (await json(response)).invitation;
}

function simulatedClient() {
  const { privateKey, publicKey } = generateKeyPairSync('ed25519');
  return {
    privateKey,
    publicKey: publicKey.export({ type: 'spki', format: 'der' }).toString('base64url'),
  };
}

async function startPairing(api, invitation, client = simulatedClient()) {
  const payload = new URL(invitation.payload);
  const request = {
    protocol: Number(payload.searchParams.get('v')),
    invitationId: payload.searchParams.get('invite'),
    invitationSecret: payload.searchParams.get('secret'),
    clientPublicKey: client.publicKey,
    userDisplayName: 'Robin',
    clientDisplayName: 'Testtelefon',
  };
  const response = await fetch(`${api}/api/v1/pairing/start`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(request),
  });
  return { response, request, client };
}

test('first-run admin lifecycle and complete key-based pairing', async (suite) => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'source-node-pairing-'));
  let now = 1_800_000_000_000;
  const ollama = { async status() { return true; }, async chat() { return { role: 'assistant', content: 'Lokalt svar' }; } };
  const options = {
    databasePath: path.join(root, 'state', 'source.sqlite'),
    storageRoot: path.join(root, 'vaults'),
    pairingBaseUrl: 'https://192.168.1.10:8443/api/v1/pairing',
    pairingInvitationTtlMs: 300_000,
    pairingCaCertificatePath,
    maximumSnapshotBytes: 1_024,
    clock: () => now,
    ollama,
    logger: quietLogger,
  };
  let source = createSourceNode(options);
  let urls = await listen(source);

  try {
    await suite.test('fresh Node requires setup and admin is loopback by default', async () => {
      assert.equal(source.config.host, '127.0.0.1');
      assert.equal(source.config.adminHost, '127.0.0.1');
      assert.equal(source.config.discoveryEnabled, false);
      assert.throws(
        () => loadConfig({ adminHost: '0.0.0.0', containerAdmin: false }),
        /must be loopback/,
      );
      const state = await fetch(`${urls.admin}/admin/api/state`);
      assert.deepEqual(await json(state), {
        initialized: false,
        authenticated: false,
        suggestedNodeName: os.hostname(),
      });
      const dashboard = await fetch(`${urls.admin}/admin/api/dashboard`);
      assert.equal(dashboard.status, 401);
      const ui = await fetch(`${urls.admin}/`);
      const html = await ui.text();
      assert.equal(ui.status, 200);
      assert.match(html, /Initialize this Node/);
      assert.doesNotMatch(html, /<(?:script|link)[^>]+(?:src|href)=["']https?:/i);
    });

    let nodeId;
    await suite.test('initialization is persistent and stores only a password hash', async () => {
      const mismatch = await fetch(`${urls.admin}/admin/api/initialize`, {
        method: 'POST',
        headers: { 'content-type': 'application/json', origin: urls.admin },
        body: JSON.stringify({ displayName: 'Source hemma', password: adminPassword, passwordConfirmation: 'something else entirely' }),
      });
      assert.equal(mismatch.status, 400);
      assert.equal(source.database.isInitialized(), false);

      const response = await initialize(urls.admin);
      assert.equal(response.status, 201);
      const state = source.database.getNodeState({ includeSecrets: true });
      nodeId = state.nodeId;
      assert.equal(state.displayName, 'Source hemma');
      assert.match(state.nodeId, /^srcnode_/);
      assert.match(state.adminPasswordHash, /^argon2id\$/);
      assert.equal(state.adminPasswordHash.includes(adminPassword), false);
      assert.equal(JSON.stringify(source.database.getNodeState()).includes('privateKey'), false);

      const again = await initialize(urls.admin);
      assert.equal(again.status, 409);
      assert.equal(source.database.getNodeState().nodeId, nodeId);
    });

    const session = await login(urls.admin);

    await suite.test('admin authentication and CSRF are enforced', async () => {
      const noAuth = await fetch(`${urls.admin}/admin/api/pairing-invitations`, {
        method: 'POST', headers: { 'content-type': 'application/json', origin: urls.admin }, body: JSON.stringify({ quotaBytes: 1024 ** 3 }),
      });
      assert.equal(noAuth.status, 401);
      const noCsrf = await fetch(`${urls.admin}/admin/api/pairing-invitations`, {
        method: 'POST', headers: { cookie: session.cookie, 'content-type': 'application/json', origin: urls.admin }, body: JSON.stringify({ quotaBytes: 1024 ** 3 }),
      });
      assert.equal(noCsrf.status, 403);
      const dashboard = await fetch(`${urls.admin}/admin/api/dashboard`, { headers: { cookie: session.cookie } });
      assert.equal(dashboard.status, 200);
      assert.equal((await json(dashboard)).node.nodeId, nodeId);
    });

    await suite.test('invalid, cancelled and expired invitations cannot pair', async () => {
      const first = await invite(urls.admin, session);
      assert.equal(new URL(first.payload).protocol, 'source:');
      assert.equal(new URL(first.payload).searchParams.get('node_id'), nodeId);
      assert.ok(new URL(first.payload).searchParams.get('ca').length > 300);
      const qr = await fetch(`${urls.admin}/admin/api/pairing-invitations/${first.id}/qr.svg`, {
        headers: { cookie: session.cookie },
      });
      assert.equal(qr.status, 200);
      assert.match(await qr.text(), /<svg[^>]+>/);
      const secondWhileActive = await fetch(`${urls.admin}/admin/api/pairing-invitations`, {
        method: 'POST', headers: adminHeaders(session, urls.admin, true), body: JSON.stringify({ quotaBytes: 1024 ** 3 }),
      });
      assert.equal(secondWhileActive.status, 409);

      const wrong = structuredClone(first);
      const wrongUrl = new URL(wrong.payload);
      wrongUrl.searchParams.set('secret', 'x'.repeat(43));
      wrong.payload = wrongUrl.toString();
      assert.equal((await startPairing(urls.api, wrong)).response.status, 404);

      const begun = await startPairing(urls.api, first);
      assert.equal(begun.response.status, 200);
      const begunChallenge = await json(begun.response);
      const cancel = await fetch(`${urls.admin}/admin/api/pairing-invitations/${first.id}`, {
        method: 'DELETE', headers: adminHeaders(session, urls.admin),
      });
      assert.equal(cancel.status, 204);
      assert.equal((await startPairing(urls.api, first)).response.status, 404);
      const afterCancel = await fetch(`${urls.api}/api/v1/pairing/complete`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          protocol: 1,
          invitationId: begun.request.invitationId,
          invitationSecret: begun.request.invitationSecret,
          handshakeId: begunChallenge.handshakeId,
          signature: sign(null, Buffer.from(begunChallenge.signingPayload), begun.client.privateKey).toString('base64url'),
        }),
      });
      assert.equal(afterCancel.status, 404);
      assert.equal(source.database.listUsers().length, 0);

      const expiring = await invite(urls.admin, session);
      assert.notEqual(expiring.id, first.id);
      assert.notEqual(new URL(expiring.payload).searchParams.get('secret'), new URL(first.payload).searchParams.get('secret'));
      now += 300_001;
      assert.equal((await startPairing(urls.api, expiring)).response.status, 404);
      assert.equal(source.pairing.getInvitation().state, 'expired');
      now += 1;
    });

    let credential;
    await suite.test('successful proof creates user and client atomically and QR cannot replay', async () => {
      const invitation = await invite(urls.admin, session, 7 * 1024 ** 3);
      const started = await startPairing(urls.api, invitation);
      assert.equal(started.response.status, 200);
      const challenge = await json(started.response);
      const nodeKey = createPublicKey({
        key: Buffer.from(new URL(invitation.payload).searchParams.get('node_key'), 'base64url'),
        type: 'spki',
        format: 'der',
      });
      assert.equal(
        verify(null, Buffer.from(challenge.signingPayload), nodeKey, Buffer.from(challenge.nodeSignature, 'base64url')),
        true,
      );
      const signature = sign(null, Buffer.from(challenge.signingPayload), started.client.privateKey).toString('base64url');
      const completionBody = {
        protocol: 1,
        invitationId: started.request.invitationId,
        invitationSecret: started.request.invitationSecret,
        handshakeId: challenge.handshakeId,
        signature,
      };
      const completed = await fetch(`${urls.api}/api/v1/pairing/complete`, {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(completionBody),
      });
      assert.equal(completed.status, 201);
      const paired = await json(completed);
      credential = paired.clientCredential;
      const storedCredential = source.database.database.prepare('SELECT credential_hash AS credentialHash FROM clients').get();
      assert.notEqual(storedCredential.credentialHash, credential);
      assert.equal(storedCredential.credentialHash.includes(credential), false);
      assert.equal(source.database.listUsers()[0].quotaBytes, 7 * 1024 ** 3);
      assert.equal(source.database.listUsers()[0].clientCount, 1);
      assert.equal(source.database.listUsers()[0].displayName, 'Robin');

      const replay = await fetch(`${urls.api}/api/v1/pairing/complete`, {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(completionBody),
      });
      assert.equal(replay.status, 404);
      assert.equal(source.database.listUsers().length, 1);

      const dashboard = await fetch(`${urls.admin}/admin/api/dashboard`, { headers: { cookie: session.cookie } });
      assert.equal((await json(dashboard)).users[0].clientCount, 1);
    });

    await suite.test('paired client credential authenticates the normal API', async () => {
      const me = await fetch(`${urls.api}/api/v1/me`, { headers: { authorization: `Bearer ${credential}` } });
      assert.equal(me.status, 200);
      assert.equal((await json(me)).user.displayName, 'Robin');
      const nonce = randomBytes(32).toString('base64url');
      const identityResponse = await fetch(`${urls.api}/api/v1/identity/challenge`, {
        method: 'POST',
        headers: { authorization: `Bearer ${credential}`, 'content-type': 'application/json' },
        body: JSON.stringify({ protocol: 1, nonce }),
      });
      assert.equal(identityResponse.status, 200);
      const identity = await json(identityResponse);
      const expectedIdentityPayload = [
        'source-node-auth-v1',
        nodeId,
        identity.clientId,
        nonce,
        Buffer.from('Source hemma', 'utf8').toString('base64url'),
      ].join('\n');
      assert.equal(identity.signingPayload, expectedIdentityPayload);
      assert.equal(identity.nodeId, nodeId);
      assert.equal(identity.nonce, nonce);
      assert.equal(
        verify(
          null,
          Buffer.from(identity.signingPayload),
          createPublicKey({ key: Buffer.from(identity.nodePublicKey, 'base64url'), type: 'spki', format: 'der' }),
          Buffer.from(identity.nodeSignature, 'base64url'),
        ),
        true,
      );
      const unauthenticatedProof = await fetch(`${urls.api}/api/v1/identity/challenge`, {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ protocol: 1, nonce }),
      });
      assert.equal(unauthenticatedProof.status, 401);
      const snapshotId = randomUUID();
      const upload = await fetch(`${urls.api}/api/v1/storage/thoughts/snapshots/${snapshotId}`, {
        method: 'PUT',
        headers: { authorization: `Bearer ${credential}`, 'content-type': 'application/octet-stream' },
        body: Buffer.alloc(64, 7),
      });
      assert.equal(upload.status, 201, 'the paired user quota permits the snapshot');
      const oldPasswordLogin = await fetch(`${urls.api}/api/v1/auth/login`, {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: '{}',
      });
      assert.equal(oldPasswordLogin.status, 401);
    });

    await suite.test('restart preserves identity/users but invalidates active invitation', async () => {
      const active = await invite(urls.admin, session);
      assert.equal((await startPairing(urls.api, active)).response.status, 200);
      await source.close();
      source = createSourceNode(options);
      urls = await listen(source);
      assert.equal(source.database.getNodeState().nodeId, nodeId);
      assert.equal(source.database.listUsers().length, 1);
      assert.equal((await startPairing(urls.api, active)).response.status, 404);
      const me = await fetch(`${urls.api}/api/v1/me`, { headers: { authorization: `Bearer ${credential}` } });
      assert.equal(me.status, 200);
    });
  } finally {
    await source.close();
    await fs.rm(root, { recursive: true, force: true });
  }
});

test('invalid client proof and malformed keys do not create a user', async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'source-node-proof-'));
  const source = createSourceNode({
    databasePath: path.join(root, 'state.sqlite'), storageRoot: path.join(root, 'vaults'),
    pairingCaCertificatePath,
    ollama: { async status() { return false; } }, logger: quietLogger,
  });
  const urls = await listen(source);
  try {
    await initialize(urls.admin);
    const session = await login(urls.admin);
    const invitation = await invite(urls.admin, session);
    const malformed = await startPairing(urls.api, invitation, { publicKey: 'not-a-key' });
    assert.equal(malformed.response.status, 400);

    const started = await startPairing(urls.api, invitation);
    const challenge = await json(started.response);
    const failed = await fetch(`${urls.api}/api/v1/pairing/complete`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        protocol: 1,
        invitationId: started.request.invitationId,
        invitationSecret: started.request.invitationSecret,
        handshakeId: challenge.handshakeId,
        signature: Buffer.alloc(64).toString('base64url'),
      }),
    });
    assert.equal(failed.status, 401);
    assert.equal(source.database.listUsers().length, 0);
  } finally {
    await source.close();
    await fs.rm(root, { recursive: true, force: true });
  }
});
