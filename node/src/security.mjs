import {
  argon2Sync,
  createHash,
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

export function normalizeUsername(username) {
  if (typeof username !== 'string') throw new Error('Username is required');
  const normalized = username.trim().toLowerCase();
  if (!/^[a-z0-9][a-z0-9._-]{2,63}$/.test(normalized)) {
    throw new Error('Username must be 3-64 lowercase letters, digits, dot, underscore or dash');
  }
  return normalized;
}

export function generatePassword() {
  return `${randomBytes(12).toString('base64url')}-${randomBytes(6).toString('base64url')}`;
}

export function generateToken() {
  return randomBytes(32).toString('base64url');
}

export function tokenHash(token) {
  return createHash('sha256').update(token, 'utf8').digest('hex');
}
