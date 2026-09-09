const SYSTEM_PROMPT = [
  'Du är en privat, lokalt körd assistent i Source.',
  'Svara på samma språk som användaren och var tydlig och kortfattad.',
  'Du har ingen internetåtkomst och får inte låtsas att du har sökt på nätet.',
  'Du har ingen automatisk åtkomst till användarens anteckningar.',
].join(' ');

export class OllamaClient {
  constructor(config, fetchImplementation = fetch) {
    this.url = config.ollamaUrl.replace(/\/$/, '');
    this.model = config.ollamaModel;
    this.timeoutMs = config.ollamaTimeoutMs;
    this.fetch = fetchImplementation;
  }

  async status() {
    try {
      const response = await this.fetch(`${this.url}/api/tags`, {
        signal: AbortSignal.timeout(Math.min(this.timeoutMs, 1_500)),
      });
      if (!response.ok) return false;
      const body = await response.json();
      return Array.isArray(body.models) && body.models.some((model) => {
        const name = model.name ?? model.model;
        return name === this.model || name?.startsWith(`${this.model}:`);
      });
    } catch {
      return false;
    }
  }

  async chat(messages) {
    const modelMessages = messages.map((message, index) => (
      index === messages.length - 1 && message.role === 'user'
        ? { ...message, content: `${message.content}\n\n/no_think` }
        : message
    ));
    const response = await this.fetch(`${this.url}/api/chat`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({
        model: this.model,
        stream: false,
        think: false,
        messages: [{ role: 'system', content: SYSTEM_PROMPT }, ...modelMessages],
        options: { temperature: 0.6 },
      }),
      signal: AbortSignal.timeout(this.timeoutMs),
    });
    if (!response.ok) throw new Error(`Model returned HTTP ${response.status}`);
    const body = await response.json();
    let content = body?.message?.content;
    if (typeof content !== 'string' || content.trim() === '') {
      throw new Error('Model returned an empty response');
    }
    const closingThinkTag = content.lastIndexOf('</think>');
    if (closingThinkTag >= 0) content = content.slice(closingThinkTag + '</think>'.length);
    if (content.includes('<think>') || content.trim() === '') {
      throw new Error('Model returned reasoning without a usable answer');
    }
    return { role: 'assistant', content: content.trim() };
  }
}
