import assert from 'node:assert/strict';
import test from 'node:test';
import { SourceDiscovery } from '../src/discovery.mjs';

test('DNS-SD advertises only public Source routing hints', async () => {
  let published;
  let stopped = false;
  let destroyed = false;
  const bonjour = {
    publish(options) {
      published = options;
      return { stop(callback) { stopped = true; callback(); } };
    },
    destroy() { destroyed = true; },
  };
  const discovery = new SourceDiscovery({
    database: {
      isInitialized: () => true,
      getNodeState: () => ({ displayName: 'Source hemma', nodeId: `srcnode_${'a'.repeat(43)}` }),
    },
    config: { httpsPort: 8443 },
    bonjourFactory: () => bonjour,
    logger: { info() {} },
  });

  discovery.start();
  assert.deepEqual(published, {
    name: 'Source aaaaaaaa',
    type: 'source',
    protocol: 'tcp',
    port: 8443,
    txt: { v: '1', id: `srcnode_${'a'.repeat(43)}`, name: 'Source hemma', api: '/api/v1' },
  });
  assert.equal(JSON.stringify(published).includes('secret'), false);
  await discovery.stop();
  assert.equal(stopped, true);
  assert.equal(destroyed, true);
});
