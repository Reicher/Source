#!/usr/bin/env node
import { loadConfig } from './config.mjs';
import { HubDatabase } from './database.mjs';
import { SnapshotStorage } from './storage.mjs';
import { generatePassword, hashPassword, normalizeUsername } from './security.mjs';

function usage() {
  console.error(`Usage:
  node src/admin.mjs create <username>
  node src/admin.mjs list
  node src/admin.mjs reset-password <username>
  node src/admin.mjs sessions <username>
  node src/admin.mjs revoke-session <username> <session-id>
  node src/admin.mjs disable <username>
  node src/admin.mjs enable <username>
  node src/admin.mjs delete <username> --confirm`);
  process.exitCode = 2;
}

async function main() {
  const [command, rawUsername, argument] = process.argv.slice(2);
  const config = loadConfig();
  const database = new HubDatabase(config.databasePath);
  const storage = new SnapshotStorage(database, config);
  try {
    if (command === 'list') {
      for (const user of database.listUsers()) {
        console.log(`${user.username}\t${user.disabledAt === null ? 'active' : 'disabled'}\t${user.id}`);
      }
      return;
    }
    if (!rawUsername) return usage();
    const username = normalizeUsername(rawUsername);
    const existing = database.findUserByUsername(username);

    if (command === 'create') {
      if (existing) throw new Error(`User ${username} already exists`);
      const password = generatePassword();
      const user = database.createUser({
        username,
        passwordHash: await hashPassword(password),
        now: config.clock(),
      });
      console.log(`created ${user.username} (${user.id})`);
      console.log(`initial password: ${password}`);
      console.log('The password is shown once. Transfer it directly to the user.');
      return;
    }

    if (!existing) throw new Error(`User ${username} does not exist`);
    if (command === 'reset-password') {
      const password = generatePassword();
      database.updatePassword({
        userId: existing.id,
        passwordHash: await hashPassword(password),
        now: config.clock(),
      });
      console.log(`password reset and all sessions revoked for ${username}`);
      console.log(`new password: ${password}`);
      return;
    }
    if (command === 'sessions') {
      for (const session of database.listSessions(existing.id)) {
        const state = session.revokedAt === null ? 'active' : 'revoked';
        console.log(`${session.id}\t${state}\t${session.deviceName}\t${new Date(session.createdAt).toISOString()}`);
      }
      return;
    }
    if (command === 'revoke-session') {
      if (!argument) throw new Error('revoke-session requires a session id');
      const session = database.findSessionById(argument);
      if (!session || session.userId !== existing.id) {
        throw new Error(`Session ${argument} does not belong to ${username}`);
      }
      database.revokeSession({ id: session.id, now: config.clock() });
      console.log(`revoked ${session.id} (${session.deviceName}) for ${username}`);
      return;
    }
    if (command === 'disable') {
      database.disableUser({ userId: existing.id, now: config.clock() });
      console.log(`disabled ${username} and revoked all sessions`);
      return;
    }
    if (command === 'enable') {
      database.enableUser(existing.id);
      console.log(`enabled ${username}`);
      return;
    }
    if (command === 'delete') {
      if (argument !== '--confirm') {
        throw new Error('Deletion requires --confirm. This removes all server-side ciphertext.');
      }
      await storage.deleteUser(existing.id);
      database.deleteUser(existing.id);
      console.log(`deleted ${username}, its sessions, metadata and stored ciphertext`);
      return;
    }
    usage();
  } finally {
    database.close();
  }
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
