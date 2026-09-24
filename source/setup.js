const jobsRoot = document.getElementById('jobs');

if (jobsRoot) {
  const queued = document.getElementById('queued');
  const completed = document.getElementById('completed');
  const queuedCount = document.getElementById('queued-count');
  const completedCount = document.getElementById('completed-count');

  function jobMeta(job) {
    if (job.kind === 'silver_extraction') {
      return `Silver extraction · ${job.state.replace('_', ' ')}`;
    }
    return `Sync · ${job.direction === 'to_source' ? 'to Source' : 'to Self'}`;
  }

  function jobRow(job, showTime) {
    const row = document.createElement('div');
    row.className = 'job';
    const title = document.createElement('div');
    title.textContent = job.title;
    const meta = document.createElement('div');
    meta.className = 'meta';
    meta.textContent = jobMeta(job);
    row.append(title, meta);
    if (showTime) {
      const time = document.createElement('div');
      time.className = 'time';
      time.textContent = new Date(job.completed_at).toLocaleString([], {
        dateStyle: 'short', timeStyle: 'short'
      });
      row.append(time);
    }
    return row;
  }

  function renderList(target, jobs, showTime, total) {
    target.replaceChildren();
    if (!jobs.length) {
      const empty = document.createElement('div');
      empty.className = 'empty';
      empty.textContent = showTime ? 'No completed jobs' : 'No jobs waiting';
      target.append(empty);
      return;
    }
    jobs.forEach(job => target.append(jobRow(job, showTime)));
    if (total > jobs.length) {
      const more = document.createElement('div');
      more.className = 'more';
      more.textContent = `+ ${total - jobs.length} earlier`;
      target.append(more);
    }
  }

  async function refreshJobs() {
    try {
      const response = await fetch('/jobs', { cache: 'no-store' });
      if (!response.ok) throw new Error('Jobs unavailable');
      const jobs = await response.json();
      queuedCount.textContent = `(${jobs.queued_count})`;
      completedCount.textContent = `(${jobs.completed_count})`;
      renderList(queued, jobs.queued, false, jobs.queued_count);
      renderList(completed, jobs.completed, true, jobs.completed_count);
    } catch (_) {
      queued.replaceChildren();
      const unavailable = document.createElement('div');
      unavailable.className = 'empty';
      unavailable.textContent = 'Job status unavailable';
      queued.append(unavailable);
    }
  }

  refreshJobs();
  setInterval(refreshJobs, 2000);
} else {
  const qr = document.getElementById('pairing-qr');
  let qrUrl;
  let qrTimer;
  let paired = false;

  function clearQr() {
    qr.removeAttribute('src');
    if (qrUrl) URL.revokeObjectURL(qrUrl);
    qrUrl = undefined;
  }

  async function refreshQr() {
    if (paired) return;
    try {
      const response = await fetch('/qr.png', { cache: 'no-store' });
      if (!response.ok) throw new Error('QR unavailable');
      const expiresIn = Number(response.headers.get('X-QR-Expires-In-Ms'));
      if (!Number.isFinite(expiresIn) || expiresIn <= 0) throw new Error('QR expired');
      const deadline = performance.now() + expiresIn;
      const next = URL.createObjectURL(await response.blob());
      if (paired) {
        URL.revokeObjectURL(next);
        return;
      }
      if (performance.now() >= deadline) {
        URL.revokeObjectURL(next);
        qrTimer = setTimeout(refreshQr, 50);
        return;
      }
      clearQr();
      qrUrl = next;
      qr.src = next;
      qrTimer = setTimeout(() => {
        clearQr();
        refreshQr();
      }, Math.max(0, deadline - performance.now() + 25));
    } catch (_) {
      clearQr();
      qrTimer = setTimeout(refreshQr, 1000);
    }
  }

  refreshQr();
  const statusTimer = setInterval(async () => {
    try {
      if (await (await fetch('/paired', { cache: 'no-store' })).text() === 'yes') {
        paired = true;
        clearTimeout(qrTimer);
        clearInterval(statusTimer);
        clearQr();
        location.reload();
      }
    } catch (_) {}
  }, 1000);
}
