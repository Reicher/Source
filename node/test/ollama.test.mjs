import assert from 'node:assert/strict';
import test from 'node:test';
import { OllamaClient } from '../src/ollama.mjs';

test('Ollama client disables and filters model reasoning', async () => {
  let requestBody;
  const fakeFetch = async (_url, options) => {
    requestBody = JSON.parse(options.body);
    return new Response(JSON.stringify({
      message: {
        role: 'assistant',
        thinking: 'internal reasoning',
        content: '<think>legacy reasoning</think>\n\nklar',
      },
    }), { status: 200, headers: { 'content-type': 'application/json' } });
  };
  const client = new OllamaClient({
    ollamaUrl: 'http://ollama:11434',
    ollamaModel: 'qwen3:4b',
    ollamaTimeoutMs: 5_000,
  }, fakeFetch);

  assert.deepEqual(await client.chat([{ role: 'user', content: 'Hej' }]), {
    role: 'assistant',
    content: 'klar',
  });
  assert.equal(requestBody.think, false);
  assert.equal(requestBody.messages.at(-1).content, 'Hej\n\n/no_think');
});

test('Ollama client rejects reasoning without an answer', async () => {
  const fakeFetch = async () => new Response(JSON.stringify({
    message: { content: '<think>only reasoning</think>' },
  }), { status: 200, headers: { 'content-type': 'application/json' } });
  const client = new OllamaClient({
    ollamaUrl: 'http://ollama:11434',
    ollamaModel: 'qwen3:4b',
    ollamaTimeoutMs: 5_000,
  }, fakeFetch);

  await assert.rejects(
    client.chat([{ role: 'user', content: 'Hej' }]),
    /reasoning without a usable answer/,
  );
});
