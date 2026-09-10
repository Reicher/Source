import assert from 'node:assert/strict';
import test from 'node:test';
import { LlamaCppClient } from '../src/llama-cpp.mjs';

test('llama.cpp client rejects an empty completion', async () => {
  const fakeFetch = async () => new Response('data: [DONE]\n\n', {
    status: 200,
    headers: { 'content-type': 'text/event-stream' },
  });
  const client = new LlamaCppClient({
    llamaUrl: 'http://llama:8080',
    llamaModel: 'source-qwen3.5-9b',
    llamaTimeoutMs: 5_000,
  }, fakeFetch);

  await assert.rejects(async () => {
    for await (const _event of client.streamChat([{ role: 'user', content: 'Hej' }])) { /* consume */ }
  }, /empty response/);
});

test('llama.cpp client streams only visible content and completion metadata', async () => {
  let requestBody;
  const encoder = new TextEncoder();
  const fakeFetch = async (_url, options) => {
    requestBody = JSON.parse(options.body);
    return new Response(new ReadableStream({
      start(controller) {
        controller.enqueue(encoder.encode('data: {"choices":[{"delta":{"reasoning_content":"hidden"}}]}\n\n'));
        controller.enqueue(encoder.encode('data: {"choices":[{"delta":{"content":"Hej"}}]}\n'));
        controller.enqueue(encoder.encode('data: {"choices":[{"delta":{"content":"!"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n'));
        controller.close();
      },
    }), { status: 200, headers: { 'content-type': 'text/event-stream' } });
  };
  const client = new LlamaCppClient({
    llamaUrl: 'http://llama:8080',
    llamaModel: 'source-qwen3.5-9b',
    llamaMaximumOutputTokens: 2_048,
    llamaTimeoutMs: 5_000,
  }, fakeFetch);

  const events = [];
  for await (const event of client.streamChat([{ role: 'user', content: 'Hej' }])) events.push(event);
  assert.deepEqual(events, [
    { type: 'delta', text: 'Hej' },
    { type: 'delta', text: '!' },
    { type: 'completed', finishReason: 'stop' },
  ]);
  assert.equal(requestBody.stream, true);
  assert.deepEqual(requestBody.messages, [{ role: 'user', content: 'Hej' }]);
  assert.equal(Object.hasOwn(requestBody, 'system'), false);
});

test('llama.cpp client rejects hidden system messages', async () => {
  const client = new LlamaCppClient({
    llamaUrl: 'http://llama:8080',
    llamaModel: 'source-qwen3.5-9b',
    llamaTimeoutMs: 5_000,
  }, async () => { throw new Error('fetch should not run'); });

  await assert.rejects(async () => {
    for await (const _event of client.streamChat([{ role: 'system', content: 'Hidden prompt' }])) { /* consume */ }
  }, /Only explicit user and assistant/);
});

test('llama.cpp client forwards cancellation, including an already aborted request', async () => {
  let receivedSignal;
  const client = new LlamaCppClient({
    llamaUrl: 'http://llama:8080',
    llamaModel: 'source-qwen3.5-9b',
    llamaTimeoutMs: 5_000,
  }, async (_url, options) => {
    receivedSignal = options.signal;
    throw options.signal.reason;
  });
  const request = new AbortController();
  request.abort(new Error('client disconnected'));

  await assert.rejects(async () => {
    for await (const _event of client.streamChat([{ role: 'user', content: 'Hej' }], request.signal)) { /* consume */ }
  }, /client disconnected/);
  assert.equal(receivedSignal.aborted, true);
});

test('llama.cpp status uses the runtime health endpoint', async () => {
  let requestedUrl;
  const client = new LlamaCppClient({
    llamaUrl: 'http://llama:8080',
    llamaModel: 'source-qwen3.5-9b',
    llamaTimeoutMs: 5_000,
  }, async (url) => {
    requestedUrl = url;
    return new Response('{"status":"ok"}', { status: 200 });
  });

  assert.equal(await client.status(), true);
  assert.equal(requestedUrl, 'http://llama:8080/health');
});
