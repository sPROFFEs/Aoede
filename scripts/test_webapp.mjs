// Run: node --experimental-vm-modules scripts/test_webapp.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const listeners = new Map();
const stores = new Map();
const key = (request) => new URL(typeof request === 'string' ? request : request.url, 'https://aoede.test').href;
const caches = {
  async open(name) {
    if (!stores.has(name)) stores.set(name, new Map());
    const store = stores.get(name);
    return {
      async put(request, response) { store.set(key(request), response.clone()); },
      async match(request) { return store.get(key(request))?.clone(); },
      async delete(request) { return store.delete(key(request)); },
      async keys() { return [...store.keys()].map(url => ({ url })); }
    };
  },
  async keys() { return [...stores.keys()]; },
  async delete(name) { return stores.delete(name); },
  async match(request) {
    for (const name of stores.keys()) {
      const response = await (await this.open(name)).match(request);
      if (response) return response;
    }
  }
};
let fetcher = async () => new Response('online');
vm.runInNewContext(await readFile(new URL('../Aoede/assets/sw.js', import.meta.url), 'utf8'), {
  self: { location: { origin: 'https://aoede.test' }, addEventListener: (name, callback) => listeners.set(name, callback),
    skipWaiting() {}, clients: { claim() {} } },
  caches, fetch: (request) => fetcher(request), URL, Response, Date, Promise
});
async function event(name, request) {
  const waits = [];
  let response;
  listeners.get(name)({ request, waitUntil(promise) { waits.push(promise); }, respondWith(promise) { response = promise; } });
  const value = await response;
  await Promise.all(waits);
  return value;
}
const request = (path, mode = 'cors', method = 'GET') => ({
  url: `https://aoede.test${path}`, mode, method, headers: new Headers({ accept: 'text/html' })
});
const redirected = () => {
  const response = new Response('login page');
  Object.defineProperty(response, 'redirected', { value: true });
  return response;
};
fetcher = async (url) => url === '/portal' ? redirected() : new Response('asset');
await event('install');
assert.equal(await caches.match('/portal'), undefined); // Pre-login precaching cannot become an offline login shell.
const current = [...stores.keys()][0];
await caches.open('another-app-cache');
await caches.open('aoede-shell-old');
await event('activate');
assert.ok(stores.has('another-app-cache'));
assert.ok(!stores.has('aoede-shell-old'));
fetcher = async () => new Response('music shell');
await event('fetch', request('/portal', 'navigate'));
assert.equal(await (await caches.match('/portal')).text(), 'music shell');
fetcher = async () => { throw new TypeError('offline'); };
assert.equal(await (await event('fetch', request('/', 'navigate'))).text(), 'music shell');
for (const path of ['/api/playlists', '/admin/api/system-stats']) {
  const response = await event('fetch', request(path));
  assert.equal(response.status, 503);
  assert.equal((await response.json()).offline, true);
}
const admin = await event('fetch', request('/admin/system', 'navigate'));
assert.equal(admin.status, 503);
assert.ok(!(await admin.text()).includes('music shell'));
assert.equal(await event('fetch', request('/user/settings')), undefined); // HTMX handles its actual connection failure.
fetcher = async () => redirected();
await event('fetch', request('/portal', 'navigate'));
assert.equal(await caches.match('/portal'), undefined);
await (await caches.open(current)).put('/portal', new Response('cached user'));
await event('fetch', request('/logout', 'navigate'));
assert.equal(await caches.match('/portal'), undefined);

// Execute the real router: imported settings/admin pages participate in Back history.
const history = [];
const handlers = new Map();
let navigation;
const container = { children: [], style: {}, innerHTML: '' };
const document = { body: { addEventListener() {} }, getElementById: () => container };
const window = { location: { pathname: '/admin/system', assign(url) { navigation = url; } },
  history: { state: null, replaceState(value) { this.state = value; history.push(['replace', value]); },
    pushState(value) { this.state = value; history.push(['push', value]); } },
  addEventListener(name, handler) { handlers.set(name, handler); } };
const context = vm.createContext({ window, document, console, URL, requestAnimationFrame(callback) { callback(); },
  fetch: async () => ({ redirected: true, url: 'https://aoede.test/login' }) });
const module = new vm.SourceTextModule(await readFile(new URL('../Aoede/assets/js/modules/views.js', import.meta.url), 'utf8'), { context });
await module.link(name => {
  const values = name === './state.js' ? { setCurrentViewName() {}, setCurrentViewParam() {} }
    : name === './system_monitor.js' ? { initSystemMonitor() {} }
    : name === './admin_downloads.js' ? { initDownloadManager() {} } : {};
  return new vm.SyntheticModule(Object.keys(values), function () {
    for (const [key, value] of Object.entries(values)) this.setExport(key, value);
  }, { context });
});
await module.evaluate();
module.namespace.initSpaHistory();
assert.equal(history[0][1].aoedeView, 'system_monitor');
await module.namespace.loadView('settings_user');
assert.equal(history.at(-1)[1].aoedeView, 'settings_user');
const count = history.length;
await handlers.get('popstate')({ state: { aoedeView: 'system_monitor' } });
await new Promise(setImmediate);
assert.equal(history.length, count);
await module.namespace.loadView('settings_admin');
assert.equal(navigation, '/admin/');
console.log('PASS: proxy-independent navigation history, API offline errors, safe shell caching and logout invalidation.');

// Deferred actions keep their account and order; a 401/503 cannot discard changes.
const actions = [
  { action_id: 1, type: 'toggle_favorite', username: 'bob', payload: { trackId: 1, liked: true } },
  { action_id: 2, type: 'toggle_favorite', payload: { trackId: 2, liked: true } },
  { action_id: 3, type: 'toggle_favorite', username: 'alice', payload: { trackId: 3, liked: true } },
  { action_id: 4, type: 'toggle_favorite', username: 'alice', payload: { trackId: 4, liked: true } }
];
const resultRequest = result => {
  const req = { result };
  queueMicrotask(() => req.onsuccess?.());
  return req;
};
const db = { transaction() { return { objectStore() { return {
  getAll: () => resultRequest([...actions]),
  delete(id) { actions.splice(actions.findIndex(a => a.action_id === id), 1); return resultRequest(true); },
  add(action) { actions.push(action); return resultRequest(5); }
}; } }; } };
const sent = [];
let status = 503;
const offlineContext = vm.createContext({
  window: { USER_DATA: { username: 'alice' }, indexedDB: { open: () => resultRequest(db) } },
  navigator: { onLine: true }, console, Date, Math,
  fetch: async path => { sent.push(path); return { ok: status === 200, status }; }
});
const offline = new vm.SourceTextModule(await readFile(new URL('../Aoede/assets/js/modules/offline_store.js', import.meta.url), 'utf8'), { context: offlineContext });
await offline.link(() => { throw new Error('Unexpected dependency'); });
await offline.evaluate();
await Promise.all([offline.namespace.flushPendingActions(), offline.namespace.flushPendingActions()]);
assert.deepEqual(sent, ['/api/favorites/3']);
assert.equal(actions.length, 4);
status = 401;
await offline.namespace.flushPendingActions();
assert.equal(actions.length, 4);
status = 200;
await offline.namespace.flushPendingActions();
assert.deepEqual(actions.map(a => a.action_id), [1, 2]);
await offline.namespace.queueAction('toggle_favorite', { trackId: 5, liked: true });
assert.equal(actions.at(-1).username, 'alice');
console.log('PASS: account-bound deferred actions, single synchronization and retained changes on failed connections.');
