import { Bonjour } from 'bonjour-service';

export const SOURCE_DNS_SD_TYPE = 'source';
export const SOURCE_DNS_SD_PROTOCOL = 'tcp';

/** Advertises only public routing and identity hints. No secret is put in TXT. */
export class SourceDiscovery {
  constructor({ database, config, bonjourFactory = () => new Bonjour(), logger = console }) {
    this.database = database;
    this.config = config;
    this.bonjourFactory = bonjourFactory;
    this.logger = logger;
    this.bonjour = null;
    this.service = null;
    this.timer = null;
  }

  start() {
    if (this.timer) return;
    this.#publishIfReady();
    // A Node can be initialized while the process is running. This low-rate
    // local check makes the DNS-SD advertisement appear without a restart.
    this.timer = setInterval(() => this.#publishIfReady(), 2_000);
    this.timer.unref?.();
  }

  #publishIfReady() {
    if (this.service || !this.database.isInitialized()) return;
    const node = this.database.getNodeState();
    this.bonjour = this.bonjourFactory();
    this.service = this.bonjour.publish({
      name: `Source ${node.nodeId.slice(-8)}`,
      type: SOURCE_DNS_SD_TYPE,
      protocol: SOURCE_DNS_SD_PROTOCOL,
      port: this.config.httpsPort,
      txt: {
        v: '1',
        id: node.nodeId,
        name: node.displayName,
        api: '/api/v1',
      },
    });
    this.logger.info?.('source node discovery active', { type: '_source._tcp', port: this.config.httpsPort });
  }

  async stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    if (this.service) {
      await new Promise((resolve) => this.service.stop(resolve));
      this.service = null;
    }
    this.bonjour?.destroy();
    this.bonjour = null;
  }
}
