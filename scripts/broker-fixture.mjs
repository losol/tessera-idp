// SPDX-License-Identifier: MPL-2.0
// Deterministic LOCAL TEST provider + SMTP sink. Never deploy this service.
import http from 'node:http';
import net from 'node:net';
import { generateKeyPairSync, sign, randomUUID } from 'node:crypto';

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const wrongKey = generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey;
const kid = randomUUID();
const jwk = { ...publicKey.export({ format: 'jwk' }), kid, alg: 'RS256', use: 'sig' };
const codes = new Map();
const mail = [];
const seen = new Set();
const nextScenario = new Map();
const escape = s => String(s).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('"', '&quot;');
const base = 'http://127.0.0.1:18089';
const cases = {
  gmail: { sub: 'google-new', email: 'new@gmail.com' },
  returning: { sub: 'google-new', email: 'new@gmail.com' },
  'changed-email': { sub: 'google-new', email: 'existing@gmail.com' },
  'missing-email': { sub: 'google-new', email: null },
  workspace: { sub: 'google-workspace', email: 'new@workspace.example', hd: 'workspace.example' },
  external: { sub: 'google-external', email: 'external@example.org' },
  'external-existing': { sub: 'google-external-existing', email: 'existing@example.org' },
  'link-existing': { sub: 'google-existing', email: 'existing@gmail.com' },
  unverified: { sub: 'google-unverified', email: 'unverified@gmail.com' },
  conflict: { sub: 'google-conflict', email: 'conflict@gmail.com' },
  protected: { sub: 'google-protected', email: 'protected@gmail.com' },
  strict: { sub: 'google-strict', email: 'strict@gmail.com' },
  'apple-first': { sub: 'apple-new', email: 'relay@privaterelay.appleid.com', email_verified: 'true' },
  'apple-return': { sub: 'apple-new', email: null },
  'apple-private': { sub: 'apple-private', email: 'relay@private.icloud.com' },
  'apple-different': { sub: 'apple-different', email: 'different@privaterelay.appleid.com' },
};

const body = async req => { let result = ''; for await (const chunk of req) result += chunk; return new URLSearchParams(result); };
const page = content => `<!doctype html><html lang="en"><meta charset="utf-8"><title>Local broker fixture</title><body>${content}</body></html>`;
http.createServer(async (req, res) => {
  const url = new URL(req.url, base);
  const send = (status, content, type = 'text/html') => { res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store' }); res.end(content); };
  if (url.pathname === '/jwks') return send(200, JSON.stringify({ keys: [jwk] }), 'application/json');
  if (url.pathname === '/health') return send(200, 'ready', 'text/plain');
  if (url.pathname === '/control') {
    nextScenario.set(url.searchParams.get('provider'), url.searchParams.get('scenario'));
    return send(200, 'fixture scenario selected', 'text/plain');
  }
  if (url.pathname === '/mail') {
    const decoded = mail.filter(raw => !url.searchParams.get('to') || raw.includes(url.searchParams.get('to'))).map(raw => {
      let result = raw.replace(/=\r?\n/g, '').replace(/=([0-9A-F]{2})/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16)));
      for (const block of raw.split(/\r?\n--/)) {
        if (/Content-Transfer-Encoding: base64/i.test(block)) {
          const encoded = block.split(/\r?\n\r?\n/).slice(1).join('\n').trim();
          result += '\n' + Buffer.from(encoded, 'base64').toString();
        }
      }
      return result;
    }).join('\n');
    const links = [...new Set(decoded.match(/https?:\/\/[^\s<>"']+/g) ?? [])].filter(link => link.includes('/login-actions/'));
    return send(200, page(`<h1>Fixture mail</h1>${links.map(link => `<p><a href="${escape(link.replaceAll('&amp;', '&'))}">Confirm email link</a></p>`).join('')}<pre>${escape(decoded)}</pre>`));
  }
  if (url.pathname === '/app/callback') return send(200, page(`<h1>${url.searchParams.has('error') ? 'Login failed' : 'Login complete'}</h1><p>Local fixture callback reached.</p>`));
  if (url.pathname.endsWith('/authorize')) {
    const provider = url.pathname.split('/')[1];
    const automatic = url.searchParams.get('fixture_scenario') || nextScenario.get(provider);
    nextScenario.delete(provider);
    if (automatic) return send(200, page(`<h1>Automatic fixture consent: ${escape(automatic)}</h1><form method="post" action="/choose"><input type="hidden" name="request" value="${escape(url.href)}"><input type="hidden" name="scenario" value="${escape(automatic)}"><button>Continue</button></form><script>document.querySelector('form').submit()</script>`));
    return send(200, page(`<h1>${provider} fixture consent</h1><form method="post" action="/choose"><input type="hidden" name="request" value="${escape(url.href)}">${[...Object.keys(cases).filter(x => provider === 'apple' ? x.startsWith('apple-') || ['link-existing', 'unverified', 'conflict', 'protected', 'strict'].includes(x) : !x.startsWith('apple-')), 'bad-audience', 'bad-issuer', 'bad-nonce', 'missing-nonce', 'missing-exp', 'bad-signature', 'expired', 'missing-state', 'bad-state', 'token-error', 'cancel', 'failure'].map(name => `<p><button name="scenario" value="${name}">${name}</button></p>`).join('')}</form>`));
  }
  if (url.pathname === '/choose') {
    const form = await body(req);
    const original = new URL(form.get('request'));
    const provider = original.pathname.split('/')[1];
    const scenario = form.get('scenario');
    const redirect = new URL(original.searchParams.get('redirect_uri'));
    // The fixture is never an open redirect to arbitrary remote websites.
    if (!['127.0.0.1', 'localhost'].includes(redirect.hostname)) return send(400, 'Loopback callbacks only');
    const response = new URLSearchParams({ state: original.searchParams.get('state') ?? '' });
    if (scenario === 'missing-state') response.delete('state');
    if (scenario === 'bad-state') response.set('state', 'wrong-state');
    if (scenario === 'cancel' || scenario === 'failure') response.set('error', scenario === 'cancel' ? 'access_denied' : 'server_error');
    else {
      const code = randomUUID();
      codes.set(code, { original, provider, scenario, profile: cases[scenario] ?? { sub: `${provider}-${scenario}`, email: 'negative@gmail.com' } });
      response.set('code', code);
      if (provider === 'apple' && !seen.has(codes.get(code).profile.sub)) response.set('user', JSON.stringify({ name: { firstName: 'Fixture', lastName: 'Person' }, email: codes.get(code).profile.email }));
      seen.add(codes.get(code).profile.sub);
    }
    if (original.searchParams.get('response_mode') === 'form_post') return send(200, page(`<form id="response" method="post" action="${escape(redirect.href)}">${[...response].map(([name,value]) => `<input type="hidden" name="${escape(name)}" value="${escape(value)}">`).join('')}<button>Continue to Tessera</button></form><script>document.querySelector('form').submit()</script>`));
    redirect.search = response.toString(); res.writeHead(302, { Location: redirect.href }); return res.end();
  }
  if (url.pathname.endsWith('/token')) {
    const form = await body(req); const entry = codes.get(form.get('code')); codes.delete(form.get('code'));
    if (!entry) return send(400, JSON.stringify({ error: 'invalid_grant' }), 'application/json');
    const { original, provider, scenario, profile } = entry;
    if (scenario === 'token-error') return send(400, JSON.stringify({ error: 'invalid_grant' }), 'application/json');
    const now = Math.floor(Date.now() / 1000);
    const claims = { iss: provider === 'apple' ? 'https://appleid.apple.com' : 'https://accounts.google.com', aud: original.searchParams.get('client_id'), iat: now, exp: now + 300, nonce: original.searchParams.get('nonce'), email_verified: true, ...profile };
    if (profile.email === null) { delete claims.email; delete claims.email_verified; }
    if (scenario === 'bad-audience') claims.aud = 'wrong-client';
    if (scenario === 'bad-issuer') claims.iss = 'https://attacker.example';
    if (scenario === 'bad-nonce') claims.nonce = 'wrong-nonce';
    if (scenario === 'missing-nonce') delete claims.nonce;
    if (scenario === 'missing-exp') delete claims.exp;
    if (scenario === 'expired') claims.exp = now - 60;
    const input = [JSON.stringify({ alg: 'RS256', kid }), JSON.stringify(claims)].map(x => Buffer.from(x).toString('base64url')).join('.');
    const jwt = `${input}.${sign('RSA-SHA256', Buffer.from(input), scenario === 'bad-signature' ? wrongKey : privateKey).toString('base64url')}`;
    return send(200, JSON.stringify({ access_token: 'fixture-access', token_type: 'Bearer', expires_in: 300, id_token: jwt }), 'application/json');
  }
  send(404, 'Not found');
}).listen(18089, '127.0.0.1', () => console.log('Broker fixture: http://127.0.0.1:18089'));

net.createServer(socket => {
  socket.setEncoding('utf8'); socket.write('220 fixture SMTP\r\n');
  let pending = '', data = false, message = '';
  socket.on('data', chunk => {
    pending += chunk;
    while (pending.includes('\n')) {
      const index = pending.indexOf('\n'), line = pending.slice(0, index + 1); pending = pending.slice(index + 1);
      if (data) {
        if (line.trim() === '.') { mail.push(message); message = ''; data = false; socket.write('250 stored\r\n'); }
        else message += line;
      } else if (/^EHLO|^HELO/i.test(line)) socket.write('250 fixture\r\n');
      else if (/^DATA/i.test(line)) { data = true; socket.write('354 Send data\r\n'); }
      else if (/^QUIT/i.test(line)) socket.end('221 Bye\r\n');
      else socket.write('250 OK\r\n');
    }
  });
}).listen(18090, '127.0.0.1');
