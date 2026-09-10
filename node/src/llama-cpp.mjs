export class LlamaCppClient {
  constructor(config, fetchImplementation = fetch) {
    this.url = config.llamaUrl.replace(/\/$/, '');
    this.model = config.llamaModel;
    this.maximumOutputTokens = config.llamaMaximumOutputTokens ?? 2_048;
    this.timeoutMs = config.llamaTimeoutMs;
    this.fetch = fetchImplementation;
  }

  async status() {
    try {
      const response = await this.fetch(`${this.url}/health`, {
        signal: AbortSignal.timeout(Math.min(this.timeoutMs, 1_500)),
      });
      return response.ok;
    } catch {
      return false;
    }
  }

  capabilities() {
    return {
      contractVersion: 1,
      modalities: ['text'],
      streaming: true,
      cancellation: true,
      maximumContextTokens: 8_192,
      promptPolicy: 'none-v1',
      reasoning: 'off',
    };
  }

  async *streamChat(messages, signal) {
    if (!Array.isArray(messages) || messages.some((message) => !['user', 'assistant'].includes(message.role))) {
      throw new Error('Only explicit user and assistant messages are allowed');
    }
    const generation = new AbortController();
    const abortFromCaller = () => generation.abort(signal?.reason);
    if (signal?.aborted) abortFromCaller();
    else signal?.addEventListener('abort', abortFromCaller, { once: true });
    const timeout = setTimeout(() => generation.abort(new Error('Model request timed out')), this.timeoutMs);
    try {
      const response = await this.fetch(`${this.url}/v1/chat/completions`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({
          model: this.model,
          messages,
          stream: true,
          temperature: 0.6,
          top_p: 0.9,
          max_tokens: this.maximumOutputTokens,
        }),
        signal: generation.signal,
      });
      if (!response.ok || !response.body) throw new Error(`Model returned HTTP ${response.status}`);

      const decoder = new TextDecoder();
      let pending = '';
      let visibleCharacters = 0;
      let finishReason = 'stop';
      for await (const chunk of response.body) {
        pending += decoder.decode(chunk, { stream: true });
        const lines = pending.split(/\r?\n/);
        pending = lines.pop() ?? '';
        for (const line of lines) {
          if (!line.startsWith('data:')) continue;
          const data = line.slice(5).trim();
          if (!data || data === '[DONE]') continue;
          const event = JSON.parse(data);
          const choice = event?.choices?.[0];
          const text = choice?.delta?.content;
          if (typeof text === 'string' && text !== '') {
            visibleCharacters += text.length;
            yield { type: 'delta', text };
          }
          if (typeof choice?.finish_reason === 'string') finishReason = choice.finish_reason;
        }
      }
      if (visibleCharacters === 0) throw new Error('Model returned an empty response');
      yield { type: 'completed', finishReason };
    } finally {
      clearTimeout(timeout);
      signal?.removeEventListener('abort', abortFromCaller);
    }
  }

}
