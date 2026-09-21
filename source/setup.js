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
      document.body.innerHTML = '<div class="paired"></div>';
    }
  } catch (_) {}
}, 1000);
