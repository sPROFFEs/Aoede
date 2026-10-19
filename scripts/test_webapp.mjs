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
const redirected = (path = '/login') => {
  const response = new Response('login page');
  Object.defineProperty(response, 'redirected', { value: true });
  Object.defineProperty(response, 'url', { value: `https://aoede.test${path}` });
  return response;
};
fetcher = async (url) => url === '/portal' ? redirected() : new Response('asset');
await event('install');
assert.equal(await caches.match('/portal'), undefined); // Pre-login precaching cannot become an offline login shell.
assert.ok(await caches.match('/assets/js/modules/about.js')); // The About dialog also opens offline before its first use.
const current = [...stores.keys()][0];
await caches.open('another-app-cache');
await caches.open('aoede-shell-old');
await event('activate');
assert.ok(stores.has('another-app-cache'));
assert.ok(!stores.has('aoede-shell-old'));
fetcher = async () => new Response('music shell');
await event('fetch', request('/portal', 'navigate'));
assert.equal(await (await caches.match('/portal')).text(), 'music shell');
fetcher = async () => redirected('/portal');
await event('fetch', request('/', 'navigate'));
assert.ok(await caches.match('/portal')); // A normal / -> /portal redirect must preserve the offline shell.
await (await caches.open(current)).put('/portal', new Response('music shell'));
fetcher = async () => { throw new TypeError('offline'); };
assert.equal(await (await event('fetch', request('/', 'navigate'))).text(), 'music shell');
for (const path of ['/assets/css/theme_glass.css', '/assets/css/theme_vitrine.css', '/assets/js/modules/appearance.js']) {
  assert.equal(await (await event('fetch', request(`${path}?v=new-release`))).text(), 'asset');
}
await assert.rejects(event('fetch', request('/assets/not-installed.js?v=new-release')), /offline/);
fetcher = async () => new Response('fresh version');
assert.equal(await (await event('fetch', request('/assets/css/theme_glass.css?v=online'))).text(), 'fresh version');
fetcher = async () => { throw new TypeError('offline'); };
assert.equal(await (await event('fetch', request('/assets/css/theme_glass.css?v=online'))).text(), 'fresh version');
for (const path of ['/api/playlists', '/admin/api/system-stats']) {
  const response = await event('fetch', request(path));
  assert.equal(response.status, 503);
  assert.equal((await response.json()).offline, true);
}
const admin = await event('fetch', request('/admin/system', 'navigate'));
assert.equal(admin.status, 503);
assert.ok(!(await admin.text()).includes('music shell'));
assert.equal(await event('fetch', request('/user/settings')), undefined); // HTMX handles its actual connection failure.
assert.equal(await event('fetch', request('/user/appearance')), undefined); // Private preference never enters shell caching.
fetcher = async () => redirected();
await event('fetch', request('/portal', 'navigate'));
assert.equal(await caches.match('/portal'), undefined);
await (await caches.open(current)).put('/portal', new Response('cached user'));
await event('fetch', request('/logout', 'navigate'));
assert.equal(await caches.match('/portal'), undefined);

// Execute the real router: imported settings/admin pages participate in Back history.
const history = [];
const handlers = new Map();
const bodyHandlers = new Map();
let settingsMounts = 0;
let navigation;
let homeWrapped = { year: 2026, enabled: true, visible: true };
const homeCalls = [];
const container = { children: [], style: {}, innerHTML: '' };
const document = { body: { addEventListener(name, handler) { bodyHandlers.set(name, handler); } }, getElementById: () => container };
const window = { location: { pathname: '/admin/system', assign(url) { navigation = url; } },
  history: { state: null, replaceState(value) { this.state = value; history.push(['replace', value]); },
    pushState(value) { this.state = value; history.push(['push', value]); } },
  addEventListener(name, handler) { handlers.set(name, handler); } };
const context = vm.createContext({ window, document, console, URL, navigator: { onLine: true }, requestAnimationFrame(callback) { callback(); },
  localStorage: { getItem(key) { return key === 'aoede.replaygain.enabled' ? '1' : '4'; } },
  fetch: async () => ({ redirected: true, url: 'https://aoede.test/login' }) });
const module = new vm.SourceTextModule(await readFile(new URL('../Aoede/assets/js/modules/views.js', import.meta.url), 'utf8'), { context });
await module.link(name => {
  const values = name === './state.js' ? { setCurrentViewName() {}, setCurrentViewParam() {}, setCurrentViewList() {}, recentPlaylists: [], recentMixes: [], recentRadios: [] }
    : name === './ui.js' ? { initSettingsTabs() { settingsMounts++; }, escHtml: String, t: (_, fallback) => fallback, getGreeting: () => 'Hello', homeTabsBar: () => '', refreshIcons() {} }
    : name === './api.js' ? { fetchRecommendations: async () => { throw Error('Feed unavailable'); }, fetchMixes: async () => [], fetchWrapped: async (year, options) => { homeCalls.push(options); return homeWrapped; } }
    : name === './party.js' ? { fetchRooms: async () => [], renderHomeShelf: () => '<section>Parties</section>' }
    : name === './offline_store.js' ? { saveLibrarySnapshot() {}, getLibrarySnapshot: async () => ({ wrapped: { year: 2025 } }) }
    : name === './appearance.js' ? { syncThemeControls() {} }
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
const replaygain = { checked: false };
const savePrefs = { disabled: false };
const prefElements = { '#pref-replaygain': replaygain, '#pref-crossfade-chips': { querySelectorAll: () => [] }, '#pref-save-btn': savePrefs };
const settings = { querySelector: selector => prefElements[selector], querySelectorAll: () => [] };
bodyHandlers.get('htmx:afterSwap')({ target: { id: 'view-container', querySelector: () => settings } });
assert.equal(settingsMounts, 1);
assert.equal(replaygain.checked, true); // Uploaded avatar/cookies replace settings; playback controls must be initialized again.
assert.equal(savePrefs.disabled, true);
console.log('PASS: proxy-independent navigation history, API offline errors, safe shell caching and logout invalidation.');

await module.namespace.renderHome(container);
assert.equal(homeCalls[0].summary, false); // Availability must never depend on generating an annual report.
assert.match(container.innerHTML, /href="\/wrapped\/story\/2026"/);
assert.ok(container.innerHTML.indexOf('Watch Wrapped') < container.innerHTML.indexOf('quick-picks-grid'));
assert.ok(container.innerHTML.indexOf('Watch Wrapped') < container.innerHTML.indexOf('Parties'));
assert.doesNotMatch(container.innerHTML, /0 seconds listened/);
for (const unavailable of [null, { enabled: false }, { visible: false }, { error: 'Unavailable' }]) {
  homeWrapped = unavailable;
  await module.namespace.renderHome(container);
  assert.doesNotMatch(container.innerHTML, /Watch Wrapped/); // Do not revive the stale cached enabled state online.
}
console.log('PASS: Wrapped availability survives unrelated feed errors, appears above quick access, and respects enable/visibility changes.');

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

// Starting a verification browser is single-flight across provider buttons;
// failure must leave a usable retry, without enabling unavailable providers.
const verifyButtons = ['tidal', 'qobuz', 'amazon'].map(provider => ({
  dataset: { providerVerify: provider }, disabled: provider === 'amazon',
  addEventListener(_name, handler) { this.click = handler; }
}));
const browserStatus = {};
const browserFrame = {};
const modalFields = Object.fromEntries(['#auth-browser-title', '[data-auth-browser-countdown]', '[data-auth-browser-frame]', '[data-auth-browser-close]', '[data-auth-browser-stop]', '[data-auth-browser-check]'].map(selector => [selector,
  selector === '[data-auth-browser-frame]' ? browserFrame : { addEventListener() {}, focus() {} }
]));
const browserModal = { dataset: {}, hidden: true, setAttribute() {}, querySelector: selector => modalFields[selector] };
const managerRoot = {
  dataset: {}, querySelector: selector => selector === '#download-manager-status' ? browserStatus : selector === '#auth-browser-modal' ? browserModal : null,
  querySelectorAll: selector => selector === '[data-provider-verify]' ? verifyButtons : []
};
let finishBrowserRequest, failBrowserRequest;
const browserRequests = [];
const browserContext = vm.createContext({ document: {}, console,
  fetch: (url, init) => { browserRequests.push({ url, init }); return new Promise((resolve, reject) => { finishBrowserRequest = resolve; failBrowserRequest = reject; }); }
});
const managerModule = new vm.SourceTextModule(await readFile(new URL('../Aoede/assets/js/modules/admin_downloads.js', import.meta.url), 'utf8'), { context: browserContext });
await managerModule.link(() => new vm.SyntheticModule(['confirmDialog'], function () { this.setExport('confirmDialog', async () => true); }, { context: browserContext }));
await managerModule.evaluate();
managerModule.namespace.initDownloadManager({ querySelector: () => managerRoot });
const firstBrowserStart = verifyButtons[0].click();
await verifyButtons[1].click();
assert.equal(browserRequests.length, 1);
assert.ok(verifyButtons.every(button => button.disabled));
finishBrowserRequest(Response.json({ detail: 'Chromium could not start: insufficient free processes/threads.' }, { status: 503 }));
await firstBrowserStart;
assert.match(browserStatus.textContent, /insufficient free processes/);
assert.deepEqual(verifyButtons.map(button => button.disabled), [false, false, true]);
assert.equal(browserModal.hidden, true);
const failedBrowserRetry = verifyButtons[1].click();
failBrowserRequest(new TypeError('Network unavailable'));
await failedBrowserRetry;
assert.deepEqual(verifyButtons.map(button => button.disabled), [false, false, true]);
const successfulBrowserRetry = verifyButtons[1].click();
finishBrowserRequest(Response.json({ status: 'ready', novnc_url: '/admin/auth-browser/vnc.html', browser_session: { remaining_seconds: 600 } }, { status: 202 }));
await successfulBrowserRetry;
assert.equal(browserRequests.length, 3);
assert.equal(browserModal.hidden, false);
assert.equal(browserModal.dataset.provider, 'qobuz');
assert.equal(browserFrame.src, '/admin/auth-browser/vnc.html');
assert.deepEqual(verifyButtons.map(button => button.disabled), [false, false, true]);
console.log('PASS: verification startup blocks duplicate requests, preserves unavailable providers and recovers after resource/network failures.');

// Execute the real initial-route selection, including server-rendered deep links.
const entry = await readFile(new URL('../Aoede/assets/js/main.js', import.meta.url), 'utf8');
const initialRoute = entry.slice(entry.indexOf('  const restoredSession ='), entry.indexOf('  // Initialize Lucide icons'));
for (const pathname of ['/wrapped/2026', '/wrapped', '/portal']) {
  const loads = [];
  const routeContext = vm.createContext({
    window: { location: { pathname }, AOEDE_INITIAL_VIEW: 'wrapped', AOEDE_INITIAL_PARAM: 2026 },
    navigator: { onLine: true }, player: { restorePlaybackSession: async () => ({ view: 'playlist', param: 123 }) },
    views: { loadView: (...args) => loads.push(args) }, console
  });
  await vm.runInContext(`(async () => {${initialRoute}})()`, routeContext);
  assert.equal(loads.length, 1);
  assert.equal(loads[0][0], 'wrapped'); assert.equal(loads[0][1], 2026);
}
console.log('PASS: Wrapped summary deep links start the actual initial view and override a restored playback view.');
const homeExitLoads = [];
const homeExitUrls = [];
await vm.runInNewContext(`(async () => {${initialRoute}})()`, {
  window: { location: { pathname: '/portal', search: '', hash: '#home' }, AOEDE_INITIAL_VIEW: 'home', history: { state: null, replaceState: (_state, _title, url) => homeExitUrls.push(url) } },
  navigator: { onLine: true }, player: { restorePlaybackSession: async () => ({ view: 'wrapped', param: 2026 }) },
  views: { loadView: view => homeExitLoads.push(view) }, console
});
assert.deepEqual(homeExitLoads, ['home']); // Closing a story cannot reopen the summary from a saved playback session.
assert.deepEqual(homeExitUrls, ['/portal']); // Consume the intent once so later launches can restore other views.
