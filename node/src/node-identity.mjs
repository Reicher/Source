import { sign } from 'node:crypto';
import { loadNodePrivateKey } from './security.mjs';

export const NODE_AUTH_PROTOCOL_VERSION = 1;

function authError(status, code, message) {
  return Object.assign(new Error(message), { status, code });
}

function validNonce(value) {
  return typeof value === 'string'
    && value.length === 43
    && /^[A-Za-z0-9_-]+$/.test(value);
}

/**
 * Produces a fresh, credential-bound proof of the Node's permanent Ed25519
 * identity. Clients use this after DNS-SD discovery; the discovery record
 * itself is never treated as proof of identity.
 */
export function proveNodeIdentity(database, session, body) {
  if (body?.protocol !== NODE_AUTH_PROTOCOL_VERSION) {
    throw authError(400, 'unsupported_node_auth_protocol', 'Unsupported Node authentication protocol version.');
  }
  if (!validNonce(body?.nonce)) {
    throw authError(400, 'invalid_nonce', 'Nonce must be a base64url value containing at least 256 bits.');
  }

  const node = database.getNodeState({ includeSecrets: true });
  const signingPayload = [
    'source-node-auth-v1',
    node.nodeId,
    session.clientId,
    body.nonce,
    Buffer.from(node.displayName, 'utf8').toString('base64url'),
  ].join('\n');

  return {
    protocol: NODE_AUTH_PROTOCOL_VERSION,
    nodeId: node.nodeId,
    nodePublicKey: node.publicKey,
    displayName: node.displayName,
    clientId: session.clientId,
    nonce: body.nonce,
    signingPayload,
    nodeSignature: sign(
      null,
      Buffer.from(signingPayload, 'utf8'),
      loadNodePrivateKey(node.privateKey),
    ).toString('base64url'),
  };
}
