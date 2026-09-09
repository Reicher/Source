import { tokenHash } from './security.mjs';

export class AuthService {
  constructor(database, config) {
    this.database = database;
    this.config = config;
  }

  authenticate(clientCredential) {
    if (typeof clientCredential !== 'string' || clientCredential.length < 32 || clientCredential.length > 256) return null;
    const client = this.database.findClientByCredentialHash(tokenHash(clientCredential));
    if (!client || client.revokedAt !== null || client.disabledAt !== null) return null;
    this.database.touchClient(client.id, this.config.clock());
    return {
      clientId: client.id,
      user: { id: client.userId, displayName: client.userDisplayName },
    };
  }
}
