import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const script = readFileSync(new URL('./app/src/main/assets/mobile/boot.js', import.meta.url), 'utf8');
const alice = { protocol: 1, username: 'alice', is_admin: true, theme: 'vitrine', language: 'es', pool: { percent: 12, used: 1, limit: 8 } };
async function boot({ status = 200, data = alice, offline = false, cache = null, path = '/portal', worker = false } = {}) {
  const elements = Object.fromEntries(['boot-message', 'boot-retry', 'boot-settings'].map(id => [id, { hidden: true }]));
  const storage = new Map(cache ? [['aoede.android.bootstrap', JSON.stringify(cache)]] : []);
  const messages = [], redirects = [], calls = [];
  const localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) };
  const context = {
    document: { cookie: '', getElementById: id => elements[id] },
    navigator: { language: 'en-US', ...(worker ? { serviceWorker: { controller: {}, getRegistrations: async () => [{ unregister: async () => calls.push('unregister') }] } } : {}) },
    location: { pathname: path.split('?')[0], search: path.includes('?') ? '?' + path.split('?')[1] : '', replace: url => redirects.push(url), reload: () => redirects.push('reload') },
    localStorage, sessionStorage: { ...localStorage },
    window: { AoedeHost: { postMessage: value => messages.push(value) } },
    setTimeout, clearTimeout, AbortController, URLSearchParams,
    fetch: async (url, options) => {
      calls.push({ url, options });
      if (offline) throw new TypeError('Network unavailable');
      return { status, ok: status === 200, json: async () => data };
    },
  };
  vm.runInNewContext(script, context);
  for (let i = 0; i < 10; i++) await new Promise(setImmediate);
  return { elements, storage, messages, redirects, calls };
}

let result = await boot();
assert.equal(JSON.parse(result.messages[0]).data.username, 'alice');
assert.equal(JSON.parse(result.messages[0]).language, 'es');
assert.equal(JSON.parse(result.messages[0]).offline, false);
assert.equal(result.calls[0].options.credentials, 'same-origin');
assert.equal(result.calls[0].options.cache, 'no-store');
result = await boot({ status: 401, cache: alice });
assert.deepEqual(result.redirects, ['/login']);
assert.equal(result.storage.has('aoede.android.bootstrap'), false);
result = await boot({ offline: true, cache: alice });
assert.equal(JSON.parse(result.messages[0]).offline, true);
for (const args of [{ offline: true }, { status: 500, cache: alice }, { status: 503, cache: alice }, { status: 404 }, { data: { ...alice, username: '</script>' } }, { data: { ...alice, protocol: 2 } }]) {
  result = await boot(args);
  assert.equal(result.messages.length, 0);
  assert.equal(result.elements['boot-retry'].hidden, false);
  if (args.cache) assert.equal(result.storage.has('aoede.android.bootstrap'), true);
  result.elements['boot-settings'].onclick();
  assert.equal(result.messages[0], 'settings');
}
result = await boot({ path: '/login', cache: alice });
assert.equal(JSON.parse(result.messages[0]).login, true);
assert.equal(result.storage.has('aoede.android.bootstrap'), false);
assert.equal(result.calls.length, 0);
result = await boot({ path: '/login?error=Invalid%20credentials' });
assert.equal(JSON.parse(result.messages[0]).loginError, 'Invalid credentials');
result = await boot({ worker: true, cache: alice });
assert.deepEqual(result.redirects, ['reload']);
assert.equal(result.calls[0], 'unregister');
assert.equal(result.storage.has('aoede.android.bootstrap'), true);
console.log('Android frontend: authentication, offline launch, compatibility, language and wrapper migration passed');
