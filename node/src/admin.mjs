#!/usr/bin/env node
import { loadConfig } from './config.mjs';
import { SourceDatabase } from './database.mjs';

const database = new SourceDatabase(loadConfig().databasePath);
try {
  const command = process.argv[2];
  if (command === 'status') {
    const node = database.getNodeState();
    console.log(node ? `${node.displayName}\t${node.nodeId}` : 'uninitialized');
  } else if (command === 'list') {
    for (const user of database.listUsers()) {
      console.log(`${user.displayName}\t${user.clientCount} client(s)\t${user.quotaBytes} bytes\t${user.id}`);
    }
  } else {
    console.error('Usage: node src/admin.mjs status|list');
    console.error('Initialize and add users through the loopback-only admin interface.');
    process.exitCode = 2;
  }
} finally {
  database.close();
}
