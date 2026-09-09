import { randomBytes, randomUUID, sign, verify, X509Certificate } from 'node:crypto';
import fs from 'node:fs';
import {
  clientIdFromPublicKey,
  generateToken,
  loadNodePrivateKey,
  parseEd25519PublicKey,
  safeTokenHashEqual,
  tokenHash,
} from './security.mjs';

export const PAIRING_PROTOCOL_VERSION = 1;

function pairingError(status, code, message) {
  return Object.assign(new Error(message), { status, code });
}

function requiredName(value, field) {
  const name = typeof value === 'string' ? value.trim() : '';
  if (name.length < 1 || name.length > 100 || /[\u0000-\u001f\u007f]/.test(name)) {
    throw pairingError(400, `invalid_${field}`, `${field} must contain 1–100 printable characters.`);
  }
  return name;
}

function validBase64Url(value, minimum = 1, maximum = 512) {
  return typeof value === 'string'
    && value.length >= minimum
    && value.length <= maximum
    && /^[A-Za-z0-9_-]+$/.test(value);
}

function pairingCaCertificate(path) {
  try {
    const certificate = new X509Certificate(fs.readFileSync(path));
    if (!certificate.ca) throw new Error('certificate is not a CA');
    return certificate.raw.toString('base64url');
  } catch {
    throw pairingError(503, 'pairing_ca_unavailable', 'The Source pairing CA is not available.');
  }
}

export class PairingService {
  constructor(database, config) {
    this.database = database;
    this.config = config;
    this.active = null;
    this.lastResult = null;
  }

  createInvitation(quotaBytes) {
    if (!this.database.isInitialized()) throw pairingError(409, 'node_not_initialized', 'Node is not initialized.');
    this.#expireIfNeeded();
    if (this.active) throw pairingError(409, 'invitation_already_active', 'A pairing invitation is already active.');
    if (!Number.isSafeInteger(quotaBytes) || quotaBytes < 64 * 1024 * 1024 || quotaBytes > 16 * 1024 ** 4) {
      throw pairingError(400, 'invalid_quota', 'Quota must be between 64 MiB and 16 TiB.');
    }
    return this.#createInvitation({
      kind: 'create',
      quotaBytes,
    });
  }

  createRecoveryInvitation(userId) {
    if (!this.database.isInitialized()) throw pairingError(409, 'node_not_initialized', 'Node is not initialized.');
    const user = this.database.findUserById(userId);
    if (!user || user.disabledAt !== null) throw pairingError(404, 'user_not_found', 'The user was not found.');
    if (!user.recoveryKeyHash || !user.recoveryEnvelope) {
      throw pairingError(409, 'recovery_not_configured', 'The user has no recovery key.');
    }
    return this.#createInvitation({
      kind: 'recover',
      userId: user.id,
      userDisplayName: user.displayName,
      quotaBytes: user.quotaBytes,
    });
  }

  getInvitation() {
    this.#expireIfNeeded();
    if (!this.active) return this.lastResult;
    return this.#publicInvitation(this.database.getNodeState());
  }

  cancelInvitation(id) {
    this.#expireIfNeeded();
    if (!this.active || this.active.id !== id) throw pairingError(404, 'invitation_not_found', 'No active invitation was found.');
    this.active.handshakes.clear();
    this.active.secret = null;
    this.active.secretHash = null;
    this.active = null;
    this.lastResult = { id, state: 'cancelled' };
    return this.lastResult;
  }

  cancelUserInvitation(userId) {
    if (this.active?.userId !== userId) return;
    this.cancelInvitation(this.active.id);
  }

  start(body) {
    if (body?.protocol !== PAIRING_PROTOCOL_VERSION) {
      throw pairingError(400, 'unsupported_pairing_protocol', 'Unsupported pairing protocol version.');
    }
    const invitation = this.#authorizeInvitation(body?.invitationId, body?.invitationSecret);
    const userDisplayName = requiredName(body?.userDisplayName, 'user_display_name');
    const clientDisplayName = requiredName(body?.clientDisplayName, 'client_display_name');
    let parsed;
    try {
      parsed = parseEd25519PublicKey(body?.clientPublicKey);
    } catch {
      throw pairingError(400, 'invalid_client_public_key', 'The client public key is invalid.');
    }
    const clientId = clientIdFromPublicKey(parsed.der);
    if (this.database.findClientById(clientId)) {
      throw pairingError(409, 'duplicate_client', 'This client identity is already paired.');
    }
    const handshakeId = randomUUID();
    const challenge = randomBytes(32).toString('base64url');
    const signingPayload = [
      'source-pairing-v1',
      this.database.getNodeState().nodeId,
      invitation.id,
      handshakeId,
      challenge,
      clientId,
      Buffer.from(userDisplayName, 'utf8').toString('base64url'),
      Buffer.from(clientDisplayName, 'utf8').toString('base64url'),
    ].join('\n');
    invitation.handshakes.clear();
    invitation.handshakes.set(handshakeId, {
      id: handshakeId,
      clientId,
      clientPublicKey: parsed.encoded,
      publicKeyObject: parsed.key,
      userDisplayName,
      clientDisplayName,
      signingPayload,
    });
    const node = this.database.getNodeState({ includeSecrets: true });
    return {
      protocol: PAIRING_PROTOCOL_VERSION,
      handshakeId,
      challenge,
      signingPayload,
      nodeSignature: sign(null, Buffer.from(signingPayload), loadNodePrivateKey(node.privateKey)).toString('base64url'),
      expiresAt: new Date(invitation.expiresAt).toISOString(),
    };
  }

  complete(body) {
    if (body?.protocol !== PAIRING_PROTOCOL_VERSION) {
      throw pairingError(400, 'unsupported_pairing_protocol', 'Unsupported pairing protocol version.');
    }
    const invitation = this.#authorizeInvitation(body?.invitationId, body?.invitationSecret);
    const handshake = invitation.handshakes.get(body?.handshakeId);
    if (!handshake || !validBase64Url(body?.signature, 64, 128)) {
      throw pairingError(401, 'pairing_proof_failed', 'Pairing proof could not be verified.');
    }
    let proofValid = false;
    try {
      proofValid = verify(
        null,
        Buffer.from(handshake.signingPayload),
        handshake.publicKeyObject,
        Buffer.from(body.signature, 'base64url'),
      );
    } catch {
      proofValid = false;
    }
    if (!proofValid) throw pairingError(401, 'pairing_proof_failed', 'Pairing proof could not be verified.');

    const clientCredential = generateToken();
    let paired;
    try {
      const client = {
        id: handshake.clientId,
        displayName: handshake.clientDisplayName,
        publicKey: handshake.clientPublicKey,
        credentialHash: tokenHash(clientCredential),
        protocolVersion: PAIRING_PROTOCOL_VERSION,
      };
      if (invitation.kind === 'recover') {
        const user = this.database.findUserById(invitation.userId);
        if (!user || !safeTokenHashEqual(body?.recoveryKey, user.recoveryKeyHash)) {
          invitation.failedRecoveryAttempts += 1;
          if (invitation.failedRecoveryAttempts >= 5) this.cancelInvitation(invitation.id);
          throw pairingError(401, 'invalid_recovery_key', 'The recovery key is incorrect.');
        }
        paired = this.database.addRecoveredClient({
          userId: user.id,
          client,
          now: this.config.clock(),
        });
      } else {
        if (!validBase64Url(body?.recoveryKey, 43, 43) || !validBase64Url(body?.recoveryEnvelope, 80, 80)) {
          throw pairingError(400, 'invalid_recovery_material', 'Recovery material is invalid.');
        }
        paired = this.database.createPairedUser({
          displayName: handshake.userDisplayName,
          quotaBytes: invitation.quotaBytes,
          recoveryKeyHash: tokenHash(body.recoveryKey),
          recoveryEnvelope: body.recoveryEnvelope,
          client,
          now: this.config.clock(),
        });
      }
    } catch (error) {
      if (Number.isInteger(error.status)) throw error;
      if (String(error.message).includes('UNIQUE constraint failed: clients')) {
        throw pairingError(409, 'duplicate_client', 'This client identity is already paired.');
      }
      throw pairingError(503, 'pairing_persistence_failed', 'Pairing could not be completed.');
    }

    const invitationId = invitation.id;
    invitation.handshakes.clear();
    invitation.secret = null;
    invitation.secretHash = null;
    this.active = null;
    this.lastResult = {
      id: invitationId,
      state: 'paired',
      userId: paired.user.id,
      clientId: paired.client.id,
    };
    const result = {
      protocol: PAIRING_PROTOCOL_VERSION,
      nodeId: this.database.getNodeState().nodeId,
      user: { id: paired.user.id, displayName: paired.user.displayName },
      client: { id: paired.client.id, displayName: paired.client.clientDisplayName },
      clientCredential,
    };
    if (invitation.kind === 'recover') result.recoveryEnvelope = paired.user.recoveryEnvelope;
    return result;
  }

  #createInvitation(details) {
    this.#expireIfNeeded();
    if (this.active) throw pairingError(409, 'invitation_already_active', 'A pairing invitation is already active.');
    const caCertificate = pairingCaCertificate(this.config.pairingCaCertificatePath);
    const node = this.database.getNodeState();
    const secret = randomBytes(32).toString('base64url');
    const now = this.config.clock();
    this.active = {
      ...details,
      id: randomUUID(),
      secret,
      secretHash: tokenHash(secret),
      caCertificate,
      createdAt: now,
      expiresAt: now + this.config.pairingInvitationTtlMs,
      failedRecoveryAttempts: 0,
      handshakes: new Map(),
    };
    this.lastResult = null;
    return this.#publicInvitation(node);
  }

  #authorizeInvitation(id, secret) {
    this.#expireIfNeeded();
    if (
      !this.active
      || typeof id !== 'string'
      || this.active.id !== id
      || !safeTokenHashEqual(secret, this.active.secretHash)
    ) {
      throw pairingError(404, 'pairing_unavailable', 'No valid pairing invitation is available.');
    }
    return this.active;
  }

  #expireIfNeeded() {
    if (this.active && this.active.expiresAt <= this.config.clock()) {
      const id = this.active.id;
      this.active.handshakes.clear();
      this.active.secret = null;
      this.active.secretHash = null;
      this.active = null;
      this.lastResult = { id, state: 'expired' };
    }
  }

  #publicInvitation(node) {
    const payload = new URL('source://pair');
    payload.searchParams.set('v', String(PAIRING_PROTOCOL_VERSION));
    payload.searchParams.set('node_id', node.nodeId);
    payload.searchParams.set('node_key', node.publicKey);
    payload.searchParams.set('ca', this.active.caCertificate);
    payload.searchParams.set('name', node.displayName);
    payload.searchParams.set('endpoint', this.config.pairingBaseUrl);
    payload.searchParams.set('invite', this.active.id);
    payload.searchParams.set('secret', this.active.secret);
    payload.searchParams.set('expires', new Date(this.active.expiresAt).toISOString());
    if (this.active.kind === 'recover') payload.searchParams.set('action', 'recover');
    return {
      id: this.active.id,
      state: 'active',
      kind: this.active.kind,
      userId: this.active.userId,
      userDisplayName: this.active.userDisplayName,
      quotaBytes: this.active.quotaBytes,
      createdAt: new Date(this.active.createdAt).toISOString(),
      expiresAt: new Date(this.active.expiresAt).toISOString(),
      payload: payload.toString(),
    };
  }
}
