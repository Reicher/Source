import {
  argon2Sync,
  createHash,
  createPrivateKey,
  createPublicKey,
  generateKeyPairSync,
  randomBytes,
  timingSafeEqual,
} from 'node:crypto';

const ARGON2_MEMORY_KIB = 65_536;
const ARGON2_PASSES = 3;
const ARGON2_PARALLELISM = 1;
const ARGON2_TAG_LENGTH = 32;

export async function hashPassword(password) {
  validatePassword(password);
  const salt = randomBytes(16);
  const digest = argon2Sync('argon2id', {
    message: Buffer.from(password, 'utf8'),
    nonce: salt,
    parallelism: ARGON2_PARALLELISM,
    tagLength: ARGON2_TAG_LENGTH,
    memory: ARGON2_MEMORY_KIB,
    passes: ARGON2_PASSES,
  });
  return [
    'argon2id',
    'v=19',
    `m=${ARGON2_MEMORY_KIB},t=${ARGON2_PASSES},p=${ARGON2_PARALLELISM}`,
    salt.toString('base64url'),
    Buffer.from(digest).toString('base64url'),
  ].join('$');
}

export async function verifyPassword(password, encoded) {
  try {
    const [algorithm, version, parameters, saltText, digestText] = encoded.split('$');
    if (algorithm !== 'argon2id' || version !== 'v=19') return false;
    const parsed = Object.fromEntries(
      parameters.split(',').map((item) => {
        const [key, value] = item.split('=');
        return [key, Number(value)];
      }),
    );
    const expected = Buffer.from(digestText, 'base64url');
    const salt = Buffer.from(saltText, 'base64url');
    if (
      parsed.m !== ARGON2_MEMORY_KIB
      || parsed.t !== ARGON2_PASSES
      || parsed.p !== ARGON2_PARALLELISM
      || expected.length !== ARGON2_TAG_LENGTH
      || salt.length !== 16
    ) return false;
    const actual = Buffer.from(
      argon2Sync('argon2id', {
        message: Buffer.from(password, 'utf8'),
        nonce: salt,
        parallelism: parsed.p,
        tagLength: expected.length,
        memory: parsed.m,
        passes: parsed.t,
      }),
    );
    return expected.length === actual.length && timingSafeEqual(expected, actual);
  } catch {
    return false;
  }
}

export function validatePassword(password) {
  if (typeof password !== 'string' || password.length < 12 || password.length > 256) {
    throw new Error('Password must contain between 12 and 256 characters');
  }
}

export function generateToken() {
  return randomBytes(32).toString('base64url');
}

export function tokenHash(token) {
  return createHash('sha256').update(token, 'utf8').digest('hex');
}

export function generateNodeIdentity() {
  const { privateKey, publicKey } = generateKeyPairSync('ed25519');
  const publicKeyDer = publicKey.export({ type: 'spki', format: 'der' });
  return {
    nodeId: `srcnode_${createHash('sha256').update(publicKeyDer).digest('base64url')}`,
    publicKey: publicKeyDer.toString('base64url'),
    privateKey: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
  };
}

export function parseEd25519PublicKey(encoded) {
  if (typeof encoded !== 'string' || encoded.length < 40 || encoded.length > 256) {
    throw new Error('Invalid Ed25519 public key');
  }
  const der = Buffer.from(encoded, 'base64url');
  const key = createPublicKey({ key: der, type: 'spki', format: 'der' });
  if (key.asymmetricKeyType !== 'ed25519') throw new Error('Invalid Ed25519 public key');
  const canonical = key.export({ type: 'spki', format: 'der' });
  if (!canonical.equals(der)) throw new Error('Invalid Ed25519 public key');
  return { key, der, encoded: canonical.toString('base64url') };
}

export function loadNodePrivateKey(encoded) {
  const key = createPrivateKey(encoded);
  if (key.asymmetricKeyType !== 'ed25519') throw new Error('Invalid Node private key');
  return key;
}

export function clientIdFromPublicKey(publicKeyDer) {
  return `srcclient_${createHash('sha256').update(publicKeyDer).digest('base64url')}`;
}

export function safeTokenHashEqual(token, expectedHash) {
  if (typeof token !== 'string' || typeof expectedHash !== 'string' || !/^[0-9a-f]{64}$/.test(expectedHash)) return false;
  const actual = Buffer.from(tokenHash(token), 'hex');
  const expected = Buffer.from(expectedHash, 'hex');
  return timingSafeEqual(actual, expected);
}
