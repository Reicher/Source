import assert from 'node:assert/strict';
import test from 'node:test';
import { PairingService } from '../src/pairing.mjs';

test('pairing invitation fails closed when the public CA is unavailable', () => {
  const pairing = new PairingService({
    isInitialized: () => true,
    getNodeState: () => ({
      nodeId: `srcnode_${'a'.repeat(43)}`,
      publicKey: 'public-node-key',
      displayName: 'Source test',
    }),
  }, {
    clock: () => 1_900_000_000_000,
    pairingInvitationTtlMs: 300_000,
    pairingBaseUrl: 'https://192.168.1.10:8443/api/v1/pairing',
    pairingCaCertificatePath: '/definitely-missing/source-node-ca.crt',
  });

  assert.throws(
    () => pairing.createInvitation(64 * 1024 * 1024),
    (error) => error.status === 503 && error.code === 'pairing_ca_unavailable',
  );
  assert.equal(pairing.getInvitation(), null);
});
