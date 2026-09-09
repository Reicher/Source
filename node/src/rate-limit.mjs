export class RateLimiter {
  constructor({ limit, windowMs, clock = Date.now }) {
    this.limit = limit;
    this.windowMs = windowMs;
    this.clock = clock;
    this.entries = new Map();
  }

  take(key) {
    const now = this.clock();
    const current = this.entries.get(key);
    if (!current || current.resetAt <= now) {
      this.entries.set(key, { count: 1, resetAt: now + this.windowMs });
      this.#prune(now);
      return true;
    }
    if (current.count >= this.limit) return false;
    current.count += 1;
    return true;
  }

  #prune(now) {
    if (this.entries.size < 1_000) return;
    for (const [key, entry] of this.entries) {
      if (entry.resetAt <= now) this.entries.delete(key);
    }
  }
}
