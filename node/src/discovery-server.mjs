import { loadConfig } from './config.mjs';
import { SourceDatabase } from './database.mjs';
import { SourceDiscovery } from './discovery.mjs';

const config = loadConfig();
const database = new SourceDatabase(config.databasePath);
const discovery = new SourceDiscovery({ database, config });
discovery.start();

const shutdown = async () => {
  await discovery.stop();
  database.close();
  process.exit(0);
};
process.once('SIGINT', shutdown);
process.once('SIGTERM', shutdown);
