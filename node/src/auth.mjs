import { randomUUID } from 'node:crypto';
import { generateToken, tokenHash, verifyPassword } from './security.mjs';

function publicUser(sessionOrUser) {
  return {
    id: sessionOrUser.userId ?? sessionOrUser.id,
    username: sessionOrUser.username,
  };
}

export class AuthService {
  constructor(database, config) {
    this.database = database;
    this.config = config;
  }

  async login({ username, password, deviceName }) {
    const user = this.database.findUserByUsername(username);
    if (!user || user.disabledAt !== null || !(await verifyPassword(password, user.passwordHash))) {
      return null;
    }
    const now = this.config.clock();
    this.database.deleteExpiredSessions(now);
    const tokens = this.#tokens(now);
    this.database.createSession({
      id: randomUUID(),
      userId: user.id,
      deviceName,
      accessHash: tokenHash(tokens.accessToken),
      accessExpiresAt: tokens.accessExpiresAt,
      refreshHash: tokenHash(tokens.refreshToken),
      refreshExpiresAt: tokens.refreshExpiresAt,
      createdAt: now,
    });
    return this.#response(tokens, publicUser(user));
  }

  authenticate(accessToken) {
    if (!accessToken) return null;
    const session = this.database.findSessionByAccessHash(tokenHash(accessToken));
    const now = this.config.clock();
    if (
      !session ||
      session.revokedAt !== null ||
      session.disabledAt !== null ||
      session.accessExpiresAt <= now
    ) {
      return null;
    }
    return { sessionId: session.id, user: publicUser(session) };
  }

  refresh(refreshToken) {
    if (!refreshToken) return null;
    const session = this.database.findSessionByRefreshHash(tokenHash(refreshToken));
    const now = this.config.clock();
    if (
      !session ||
      session.revokedAt !== null ||
      session.disabledAt !== null ||
      session.refreshExpiresAt <= now
    ) {
      return null;
    }
    const tokens = this.#tokens(now);
    this.database.rotateSession({
      id: session.id,
      accessHash: tokenHash(tokens.accessToken),
      accessExpiresAt: tokens.accessExpiresAt,
      refreshHash: tokenHash(tokens.refreshToken),
      refreshExpiresAt: tokens.refreshExpiresAt,
    });
    return this.#response(tokens, publicUser(session));
  }

  logout(sessionId) {
    this.database.revokeSession({ id: sessionId, now: this.config.clock() });
  }

  #tokens(now) {
    return {
      accessToken: generateToken(),
      accessExpiresAt: now + this.config.accessTokenTtlMs,
      refreshToken: generateToken(),
      refreshExpiresAt: now + this.config.refreshTokenTtlMs,
    };
  }

  #response(tokens, user) {
    return {
      accessToken: tokens.accessToken,
      expiresAt: new Date(tokens.accessExpiresAt).toISOString(),
      refreshToken: tokens.refreshToken,
      refreshExpiresAt: new Date(tokens.refreshExpiresAt).toISOString(),
      user,
    };
  }
}
