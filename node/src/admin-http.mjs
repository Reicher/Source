import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import QRCode from 'qrcode';
import packageMetadata from '../package.json' with { type: 'json' };
import { generateNodeIdentity, generateToken, hashPassword, tokenHash, validatePassword, verifyPassword } from './security.mjs';
import { RateLimiter } from './rate-limit.mjs';

const ADMIN_COOKIE = 'source_admin_session';
const VERSION = packageMetadata.version;

class AdminError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function json(response, status, body, headers = {}) {
  const payload = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {
    'cache-control': 'no-store',
    'content-type': 'application/json; charset=utf-8',
    'content-length': payload.length,
    'x-content-type-options': 'nosniff',
    'x-frame-options': 'DENY',
    'content-security-policy': "default-src 'self'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
    ...headers,
  });
  response.end(payload);
}

function empty(response, status, headers = {}) {
  response.writeHead(status, { 'cache-control': 'no-store', ...headers });
  response.end();
}

function adminError(response, error) {
  const status = Number.isInteger(error.status) ? error.status : 500;
  json(response, status, {
    error: {
      code: typeof error.code === 'string' ? error.code : 'internal_error',
      message: status >= 500 ? 'Source Node could not complete the request.' : error.message,
    },
  });
}

async function readJson(request, maximumBytes = 32 * 1024) {
  if (!request.headers['content-type']?.toLowerCase().startsWith('application/json')) {
    throw new AdminError(415, 'unsupported_media_type', 'Content-Type must be application/json.');
  }
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > maximumBytes) throw new AdminError(413, 'request_too_large', 'Request is too large.');
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch {
    throw new AdminError(400, 'invalid_json', 'Request contains invalid JSON.');
  }
}

function cookie(request, name) {
  for (const item of String(request.headers.cookie ?? '').split(';')) {
    const [key, ...value] = item.trim().split('=');
    if (key === name) return value.join('=');
  }
  return null;
}

function validateDisplayName(value) {
  const name = typeof value === 'string' ? value.trim() : '';
  if (name.length < 1 || name.length > 100 || /[\u0000-\u001f\u007f]/.test(name)) {
    throw new AdminError(400, 'invalid_display_name', 'Display name must contain 1–100 printable characters.');
  }
  return name;
}

function assertSameOrigin(request) {
  const origin = request.headers.origin;
  if (!origin) return;
  let parsed;
  try { parsed = new URL(origin); } catch { throw new AdminError(403, 'invalid_origin', 'Request origin is not allowed.'); }
  if (parsed.host !== request.headers.host || parsed.protocol !== 'http:') {
    throw new AdminError(403, 'invalid_origin', 'Request origin is not allowed.');
  }
}

function temperature() {
  try {
    const zones = fs.readdirSync('/sys/class/thermal').filter((name) => name.startsWith('thermal_zone'));
    for (const zone of zones) {
      const value = Number(fs.readFileSync(`/sys/class/thermal/${zone}/temp`, 'utf8').trim());
      if (Number.isFinite(value) && value > 0) return Math.round(value > 1_000 ? value / 1_000 : value);
    }
  } catch {}
  return null;
}

function diskStatus(storageRoot) {
  try {
    const disk = fs.statfsSync(storageRoot, { bigint: true });
    const total = disk.blocks * disk.bsize;
    const available = disk.bavail * disk.bsize;
    return { totalBytes: Number(total), usedBytes: Number(total - disk.bfree * disk.bsize), availableBytes: Number(available) };
  } catch {
    return { totalBytes: null, usedBytes: null, availableBytes: null };
  }
}

class AdminSessions {
  constructor(config) {
    this.config = config;
    this.sessions = new Map();
  }

  create() {
    const token = generateToken();
    const session = { csrfToken: generateToken(), expiresAt: this.config.clock() + this.config.adminSessionTtlMs };
    this.sessions.set(tokenHash(token), session);
    return { token, ...session };
  }

  find(token) {
    if (!token) return null;
    const key = tokenHash(token);
    const session = this.sessions.get(key);
    if (!session || session.expiresAt <= this.config.clock()) {
      this.sessions.delete(key);
      return null;
    }
    return session;
  }

  delete(token) {
    if (token) this.sessions.delete(tokenHash(token));
  }
}

function sessionCookie(token, ttlMs) {
  return `${ADMIN_COOKIE}=${token}; Path=/; HttpOnly; SameSite=Strict; Max-Age=${Math.floor(ttlMs / 1000)}`;
}

function clearSessionCookie() {
  return `${ADMIN_COOKIE}=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0`;
}

async function dashboard(database, config, ai) {
  const node = database.getNodeState();
  const users = database.listUsers();
  const memoryTotal = os.totalmem();
  const memoryFree = os.freemem();
  return {
    node: {
      displayName: node.displayName,
      nodeId: node.nodeId,
      fingerprint: node.nodeId.slice(-12),
      version: VERSION,
      createdAt: new Date(node.createdAt).toISOString(),
    },
    status: {
      service: 'online',
      processUptimeSeconds: Math.floor(process.uptime()),
      systemUptimeSeconds: Math.floor(os.uptime()),
      cpu: { model: os.cpus()[0]?.model ?? null, cores: os.cpus().length, loadAverage: os.loadavg() },
      memory: { totalBytes: memoryTotal, usedBytes: memoryTotal - memoryFree, availableBytes: memoryFree },
      disk: diskStatus(config.storageRoot),
      temperatureCelsius: temperature(),
      ai: { available: await ai.status(), model: config.llamaModel },
      lan: { host: config.host, port: config.port, pairingEndpoint: config.pairingBaseUrl },
      users: users.length,
      pairedClients: users.reduce((total, user) => total + user.clientCount, 0),
    },
    users: users.map(({ storageNamespace, disabledAt, recoveryKeyHash, recoveryEnvelope, ...user }) => ({
      ...user,
      recoveryConfigured: Boolean(user.recoveryConfigured),
    })),
  };
}

export function createAdminHandler({ database, config, ai, pairing, logger = console }) {
  const sessions = new AdminSessions(config);
  const loginLimiter = new RateLimiter({ limit: 5, windowMs: 15 * 60_000, clock: config.clock });

  function authenticated(request, requireCsrf = false) {
    const session = sessions.find(cookie(request, ADMIN_COOKIE));
    if (!session) throw new AdminError(401, 'admin_authentication_required', 'Admin authentication is required.');
    if (requireCsrf && request.headers['x-source-csrf'] !== session.csrfToken) {
      throw new AdminError(403, 'csrf_failed', 'The request could not be verified.');
    }
    return session;
  }

  return async function handleAdmin(request, response) {
    const startedAt = config.clock();
    let status = 500;
    try {
      const url = new URL(request.url, 'http://source-admin');
      if (url.search) throw new AdminError(400, 'query_not_supported', 'Query parameters are not supported.');
      const route = `${request.method} ${url.pathname}`;

      if (route === 'GET /') {
        status = 200;
        const body = Buffer.from(ADMIN_HTML);
        response.writeHead(status, {
          'cache-control': 'no-store',
          'content-type': 'text/html; charset=utf-8',
          'content-length': body.length,
          'x-content-type-options': 'nosniff',
          'x-frame-options': 'DENY',
          'content-security-policy': "default-src 'self'; img-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        });
        response.end(body);
        return;
      }

      if (route === 'GET /admin/api/state') {
        const initialized = database.isInitialized();
        const session = initialized ? sessions.find(cookie(request, ADMIN_COOKIE)) : null;
        status = 200;
        json(response, status, {
          initialized,
          authenticated: Boolean(session),
          suggestedNodeName: initialized ? undefined : config.suggestedNodeName,
          csrfToken: session?.csrfToken,
        });
        return;
      }

      if (route === 'POST /admin/api/initialize') {
        assertSameOrigin(request);
        if (database.isInitialized()) throw new AdminError(409, 'already_initialized', 'Source Node is already initialized.');
        const body = await readJson(request);
        const displayName = validateDisplayName(body?.displayName);
        if (body?.password !== body?.passwordConfirmation) {
          throw new AdminError(400, 'password_mismatch', 'The passwords do not match.');
        }
        try { validatePassword(body?.password); } catch { throw new AdminError(400, 'invalid_password', 'Password must contain 12–256 characters.'); }
        const adminPasswordHash = await hashPassword(body.password);
        const node = database.initializeNode({
          displayName,
          identity: generateNodeIdentity(),
          adminPasswordHash,
          now: config.clock(),
        });
        status = 201;
        json(response, status, { initialized: true, node: { displayName: node.displayName, nodeId: node.nodeId } });
        return;
      }

      if (route === 'POST /admin/api/login') {
        assertSameOrigin(request);
        if (!database.isInitialized()) throw new AdminError(409, 'setup_required', 'Source Node must be initialized first.');
        if (!loginLimiter.take(request.socket.remoteAddress ?? 'local')) {
          throw new AdminError(429, 'too_many_attempts', 'Too many login attempts. Try again later.');
        }
        const body = await readJson(request);
        const node = database.getNodeState({ includeSecrets: true });
        if (typeof body?.password !== 'string' || !(await verifyPassword(body.password, node.adminPasswordHash))) {
          throw new AdminError(401, 'invalid_admin_credentials', 'Incorrect admin password.');
        }
        const session = sessions.create();
        status = 200;
        json(response, status, { authenticated: true, csrfToken: session.csrfToken }, {
          'set-cookie': sessionCookie(session.token, config.adminSessionTtlMs),
        });
        return;
      }

      if (route === 'POST /admin/api/logout') {
        assertSameOrigin(request);
        authenticated(request, true);
        sessions.delete(cookie(request, ADMIN_COOKIE));
        status = 204;
        empty(response, status, { 'set-cookie': clearSessionCookie() });
        return;
      }

      if (route === 'GET /admin/api/dashboard') {
        authenticated(request);
        status = 200;
        json(response, status, await dashboard(database, config, ai));
        return;
      }

      if (route === 'GET /admin/api/pairing-invitations/active') {
        authenticated(request);
        status = 200;
        json(response, status, { invitation: pairing.getInvitation() });
        return;
      }

      if (route === 'POST /admin/api/pairing-invitations') {
        assertSameOrigin(request);
        authenticated(request, true);
        const body = await readJson(request);
        const quotaBytes = Number(body?.quotaBytes);
        const invitation = pairing.createInvitation(quotaBytes);
        status = 201;
        json(response, status, { invitation });
        return;
      }

      const recoveryInvitationMatch = url.pathname.match(
        /^\/admin\/api\/users\/([0-9a-f-]{36})\/recovery-invitations$/i,
      );
      if (request.method === 'POST' && recoveryInvitationMatch) {
        assertSameOrigin(request);
        authenticated(request, true);
        const invitation = pairing.createRecoveryInvitation(recoveryInvitationMatch[1]);
        status = 201;
        json(response, status, { invitation });
        return;
      }

      const userMatch = url.pathname.match(/^\/admin\/api\/users\/([0-9a-f-]{36})$/i);
      if (request.method === 'DELETE' && userMatch) {
        assertSameOrigin(request);
        authenticated(request, true);
        const body = await readJson(request);
        const user = database.findUserById(userMatch[1]);
        if (!user) throw new AdminError(404, 'user_not_found', 'The user was not found.');
        if (body?.displayName !== user.displayName) {
          throw new AdminError(400, 'delete_confirmation_failed', 'The user name confirmation does not match.');
        }
        pairing.cancelUserInvitation(user.id);
        const disabled = database.disableUser(user.id, config.clock());
        const storageRoot = path.resolve(config.storageRoot);
        const userStorage = path.resolve(storageRoot, disabled.storageNamespace);
        if (path.dirname(userStorage) !== storageRoot) throw new AdminError(500, 'invalid_storage_path', 'User storage path is invalid.');
        await fs.promises.rm(userStorage, { recursive: true, force: true });
        database.deleteUser(user.id);
        status = 204;
        empty(response, status);
        return;
      }

      const invitationMatch = url.pathname.match(/^\/admin\/api\/pairing-invitations\/([0-9a-f-]{36})$/i);
      if (request.method === 'DELETE' && invitationMatch) {
        assertSameOrigin(request);
        authenticated(request, true);
        pairing.cancelInvitation(invitationMatch[1]);
        status = 204;
        empty(response, status);
        return;
      }

      const qrMatch = url.pathname.match(/^\/admin\/api\/pairing-invitations\/([0-9a-f-]{36})\/qr\.svg$/i);
      if (request.method === 'GET' && qrMatch) {
        authenticated(request);
        const invitation = pairing.getInvitation();
        if (!invitation || invitation.state !== 'active' || invitation.id !== qrMatch[1]) {
          throw new AdminError(404, 'invitation_not_found', 'No active invitation was found.');
        }
        const svg = await QRCode.toString(invitation.payload, { type: 'svg', errorCorrectionLevel: 'M', margin: 2, width: 360 });
        status = 200;
        response.writeHead(status, {
          'cache-control': 'no-store',
          'content-type': 'image/svg+xml; charset=utf-8',
          'content-length': Buffer.byteLength(svg),
          'x-content-type-options': 'nosniff',
        });
        response.end(svg);
        return;
      }

      throw new AdminError(404, 'not_found', 'Endpoint does not exist.');
    } catch (error) {
      status = Number.isInteger(error.status) ? error.status : 500;
      if (status >= 500) logger.error?.('admin request failed', { error: error.message });
      if (!response.headersSent) adminError(response, error);
      else response.destroy();
    } finally {
      logger.info?.(`${request.method ?? 'UNKNOWN'} ${request.url?.split('?')[0] ?? '/'} ${status} ${config.clock() - startedAt}ms`);
    }
  };
}

const ADMIN_HTML = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Source Node</title><style>
:root{color-scheme:dark;--bg:#0b0f0d;--panel:#111814;--line:#294033;--text:#e6f1e9;--muted:#94a99b;--green:#70e39e;--red:#ff807a}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace}main{width:min(980px,calc(100% - 32px));margin:48px auto}header{display:flex;justify-content:space-between;align-items:center;margin-bottom:28px}h1,h2{font-weight:600;letter-spacing:-.03em}h1{font-size:24px}h2{font-size:17px;margin:0 0 18px}.mark{color:var(--green)}.panel{border:1px solid var(--line);background:var(--panel);padding:24px;border-radius:8px;margin:16px 0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:12px}.metric{border-top:1px solid var(--line);padding-top:10px}.label,.muted{color:var(--muted);font-size:12px}.value{margin-top:4px;overflow-wrap:anywhere}label{display:block;color:var(--muted);margin:14px 0 5px}input,button{font:inherit;border-radius:5px}input{width:100%;padding:11px 12px;background:#080b09;color:var(--text);border:1px solid var(--line)}button{padding:10px 15px;border:1px solid var(--green);color:#07110b;background:var(--green);cursor:pointer}button.secondary{color:var(--text);background:transparent;border-color:var(--line)}button.danger{color:var(--red);background:transparent;border-color:var(--red)}button:disabled{opacity:.55;cursor:default}.actions{display:flex;gap:10px;margin-top:20px}.user-actions{display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap}.user-actions button{padding:7px 10px}.error{color:var(--red);min-height:23px;margin-top:12px}.user{display:grid;grid-template-columns:1.6fr .8fr .8fr 1.2fr 1.8fr;gap:12px;padding:13px 0;border-top:1px solid var(--line);align-items:center}.qr{background:white;padding:12px;width:min(384px,100%);display:block;margin:20px auto}.center{text-align:center}.hidden{display:none!important}@media(max-width:760px){main{margin:24px auto}.user{grid-template-columns:1fr 1fr}.user-actions{grid-column:1/-1;justify-content:flex-start}.panel{padding:18px}}
</style></head><body><main><header><h1><span class="mark">●</span> Source Node</h1><button id="logout" class="secondary hidden">Log out</button></header><div id="screen"></div></main><script>
let csrf='';let timer;const screen=document.querySelector('#screen'),logout=document.querySelector('#logout');
const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const bytes=n=>n==null?'Unavailable':n>=1099511627776?(n/1099511627776).toFixed(1)+' TiB':n>=1073741824?(n/1073741824).toFixed(1)+' GiB':n>=1048576?(n/1048576).toFixed(1)+' MiB':n+' B';
async function api(path,options={}){const headers={...(options.body?{'content-type':'application/json'}:{}),...(csrf?{'x-source-csrf':csrf}:{}),...options.headers};const r=await fetch(path,{...options,headers});if(r.status===204)return null;const b=await r.json();if(!r.ok)throw new Error(b.error?.message||'Request failed');return b}
function message(e){document.querySelector('.error').textContent=e.message}
function setup(name){logout.classList.add('hidden');screen.innerHTML='<section class="panel"><h2>Initialize this Node</h2><p class="muted">Create the permanent Node identity and local administrator login.</p><form id="setup"><label>Node display name</label><input name="displayName" maxlength="100" required value="'+esc(name)+'"><label>Admin password</label><input name="password" type="password" minlength="12" maxlength="256" required><label>Confirm password</label><input name="passwordConfirmation" type="password" minlength="12" maxlength="256" required><div class="error"></div><div class="actions"><button>Initialize</button></div></form></section>';document.querySelector('#setup').onsubmit=async e=>{e.preventDefault();const f=new FormData(e.target);try{await api('/admin/api/initialize',{method:'POST',body:JSON.stringify(Object.fromEntries(f))});login()}catch(x){message(x)}}}
function login(){logout.classList.add('hidden');screen.innerHTML='<section class="panel"><h2>Administrator login</h2><form id="login"><label>Admin password</label><input name="password" type="password" required autofocus><div class="error"></div><div class="actions"><button>Log in</button></div></form></section>';document.querySelector('#login').onsubmit=async e=>{e.preventDefault();try{const b=await api('/admin/api/login',{method:'POST',body:JSON.stringify({password:new FormData(e.target).get('password')})});csrf=b.csrfToken;dashboard()}catch(x){message(x)}}}
async function dashboard(){clearInterval(timer);logout.classList.remove('hidden');try{const d=await api('/admin/api/dashboard');const s=d.status,n=d.node;screen.innerHTML='<section class="panel"><h2>'+esc(n.displayName)+'</h2><div class="grid"><div class="metric"><div class="label">Node identity</div><div class="value">…'+esc(n.fingerprint)+'</div></div><div class="metric"><div class="label">Version</div><div class="value">'+esc(n.version)+'</div></div><div class="metric"><div class="label">Service</div><div class="value mark">'+esc(s.service)+'</div></div><div class="metric"><div class="label">AI / model</div><div class="value">'+(s.ai.available?'Available':'Unavailable')+' · '+esc(s.ai.model)+'</div></div><div class="metric"><div class="label">System uptime</div><div class="value">'+Math.floor(s.systemUptimeSeconds/3600)+' h</div></div><div class="metric"><div class="label">CPU</div><div class="value">'+esc(s.cpu.cores+' cores · '+(s.cpu.model||'Unavailable'))+'</div></div><div class="metric"><div class="label">Memory</div><div class="value">'+bytes(s.memory.usedBytes)+' / '+bytes(s.memory.totalBytes)+'</div></div><div class="metric"><div class="label">Storage</div><div class="value">'+bytes(s.disk.usedBytes)+' / '+bytes(s.disk.totalBytes)+'</div></div><div class="metric"><div class="label">Temperature</div><div class="value">'+(s.temperatureCelsius==null?'Unavailable':s.temperatureCelsius+' °C')+'</div></div><div class="metric"><div class="label">Pairing endpoint</div><div class="value">'+esc(s.lan.pairingEndpoint)+'</div></div></div></section><section class="panel"><div style="display:flex;justify-content:space-between;align-items:center"><h2>Users · '+d.users.length+'</h2><button id="add">Add user</button></div><div>'+d.users.map(u=>'<div class="user"><div><div>'+esc(u.displayName)+'</div><div class="muted">…'+esc(u.id.slice(-8))+'</div></div><div><div class="label">Quota</div>'+bytes(u.quotaBytes)+'</div><div><div class="label">Used</div>'+bytes(u.storageUsedBytes)+'</div><div><div class="label">Clients / recovery</div>'+u.clientCount+' · '+(u.recoveryConfigured?'Ready':'Not set')+'</div></div><div class="user-actions"><button class="secondary recover" data-id="'+esc(u.id)+'" data-name="'+esc(u.displayName)+'" '+(u.recoveryConfigured?'':'disabled')+'>Recover</button><button class="danger remove" data-id="'+esc(u.id)+'" data-name="'+esc(u.displayName)+'">Delete</button></div></div>').join('')+'</div></section>';document.querySelector('#add').onclick=addUser;document.querySelectorAll('.recover').forEach(b=>b.onclick=()=>recoverUser(b.dataset.id,b.dataset.name));document.querySelectorAll('.remove').forEach(b=>b.onclick=()=>removeUser(b.dataset.id,b.dataset.name))}catch(e){if(e.message.includes('authentication'))login();else screen.innerHTML='<div class="error">'+esc(e.message)+'</div>'}}
function addUser(){screen.innerHTML='<section class="panel"><h2>Add user</h2><p class="muted">The user and first client are created only after the client proves control of its private key.</p><form id="add-form"><label>Storage quota (GiB)</label><input name="quota" type="number" min="0.0625" max="16384" step="0.0625" value="10" required><div class="error"></div><div class="actions"><button>Create pairing invitation</button><button type="button" class="secondary" id="back">Back</button></div></form></section>';document.querySelector('#back').onclick=dashboard;document.querySelector('#add-form').onsubmit=async e=>{e.preventDefault();try{const gib=Number(new FormData(e.target).get('quota'));const b=await api('/admin/api/pairing-invitations',{method:'POST',body:JSON.stringify({quotaBytes:Math.round(gib*1073741824)})});pairing(b.invitation)}catch(x){message(x)}}}
async function recoverUser(id,name){try{const b=await api('/admin/api/users/'+encodeURIComponent(id)+'/recovery-invitations',{method:'POST'});pairing(b.invitation,'Recover '+name)}catch(x){alert(x.message)}}
async function removeUser(id,name){const confirmation=prompt('Permanently delete '+name+' and all Node data? Type the user name to confirm.');if(confirmation!==name)return;try{await api('/admin/api/users/'+encodeURIComponent(id),{method:'DELETE',body:JSON.stringify({displayName:confirmation})});dashboard()}catch(x){alert(x.message)}}
function pairing(inv,title){const p=new URL(inv.payload);screen.innerHTML='<section class="panel center"><h2>'+esc(title||'Pair user')+'</h2><p>Waiting for a Source Client…</p><div class="muted">'+esc(p.searchParams.get('name'))+' · Node …'+esc(p.searchParams.get('node_id').slice(-12))+'</div><img class="qr" alt="Pairing QR code" src="/admin/api/pairing-invitations/'+encodeURIComponent(inv.id)+'/qr.svg"><div id="count" class="value"></div><p class="muted">This single-use invitation closes after pairing, cancellation, expiry, or restart.</p><div class="error"></div><div class="actions" style="justify-content:center"><button class="danger" id="cancel">Cancel</button></div></section>';const tick=()=>{const left=Math.max(0,Math.ceil((Date.parse(inv.expiresAt)-Date.now())/1000));document.querySelector('#count').textContent='Expires in '+String(Math.floor(left/60)).padStart(2,'0')+':'+String(left%60).padStart(2,'0')};tick();timer=setInterval(async()=>{tick();try{const b=await api('/admin/api/pairing-invitations/active');if(!b.invitation||b.invitation.state!=='active'){clearInterval(timer);if(b.invitation?.state==='paired'){screen.querySelector('h2').textContent='Paired successfully';setTimeout(dashboard,1000)}else if(b.invitation?.state==='expired'){screen.querySelector('h2').textContent='Invitation expired';screen.querySelector('p').textContent='This QR code is no longer valid.';document.querySelector('#cancel').textContent='Back';document.querySelector('#cancel').className='secondary';document.querySelector('#cancel').onclick=dashboard}}}catch(x){clearInterval(timer);message(x)}},1000);document.querySelector('#cancel').onclick=async()=>{try{await api('/admin/api/pairing-invitations/'+encodeURIComponent(inv.id),{method:'DELETE'});clearInterval(timer);dashboard()}catch(x){message(x)}}}
logout.onclick=async()=>{try{await api('/admin/api/logout',{method:'POST'});}finally{csrf='';login()}};
(async()=>{const s=await api('/admin/api/state');csrf=s.csrfToken||'';if(!s.initialized)setup(s.suggestedNodeName);else if(!s.authenticated)login();else dashboard()})().catch(e=>screen.textContent=e.message);
</script></body></html>`;
