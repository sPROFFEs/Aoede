// Run: node --experimental-vm-modules scripts/test_client_updates.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const events = new Map(), fields = new Map(), messages = [], urls = [];
function element() {
  const buttons = new Map();
  return { dataset: {}, style: {}, replaceChildren() { this.innerHTML = ''; },
    querySelectorAll() { return []; }, querySelector(key) { if (!buttons.has(key)) buttons.set(key, {}); return buttons.get(key); } };
}
const notice = element(); fields.set('client-update-notice', notice);
const document = { hidden: false, getElementById: id => fields.get(id), addEventListener: (key, fn) => events.set(key, fn) };
const window = { location: { search: '?tab=updates', assign: url => urls.push(url) },
  addEventListener: (key, fn) => events.set(key, fn), setInterval() {}, sessionStorage: { getItem: () => 'system', setItem() {} },
  AOEDE_CLIENT: { updater: 1 }, AoedeHost: { postMessage: message => messages.push(message) } };
const context = vm.createContext({ console, window, document, URLSearchParams, navigator: { userAgent: 'Android AoedeAndroid/1.1.9' },
  localStorage: { getItem() { throw Error('disabled'); }, setItem() { throw Error('disabled'); } },
  AbortController, setTimeout: (...args) => setTimeout(...args).unref(), clearTimeout });
const ui = new vm.SourceTextModule(await readFile('Aoede/assets/js/modules/ui.js', 'utf8'), { context });
await ui.link(() => new vm.SyntheticModule([], function () {}, { context })); await ui.evaluate();
const client = new vm.SourceTextModule(await readFile('Aoede/assets/js/modules/client_updates.js', 'utf8'), { context });
await client.link(() => ui); await client.evaluate();
const { newerVersion, currentClient, availableUpdate, renderClientUpdate, initClientUpdates } = client.namespace;
assert(newerVersion('1.10.0', '1.9.9')); assert(!newerVersion('1.1.9', '1.1.9')); assert(!newerVersion('bad', '1.1.9'));
assert.equal(currentClient('Mozilla Android Chrome/100'), null);
assert.equal(currentClient('AoedeAndroid/1.0.5').kind, 'android-wrapper');
assert.equal(currentClient('AoedeAndroid/1.1.9').kind, 'android-native');
const mac = currentClient('Macintosh Intel Mac OS X AoedeDesktop/1.2.8');
assert.equal(mac.arch, 'universal');
assert.equal(currentClient('Linux aarch64 AoedeDesktop/1.2.8').arch, 'arm64');
const data = { release: { tag: 'v1.2.7', downloads: [
  { client: 'android-native', platform: 'android', name: 'aoede-android.apk', version: '1.2.0', url: 'https://github.com/sPROFFEs/Aoede/releases/download/v1.2.7/aoede-android.apk' },
  { client: 'desktop', platform: 'mac', arch: 'universal', name: 'aoede-mac-universal.tar.gz', version: '1.2.9', url: 'https://github.com/sPROFFEs/Aoede/releases/download/v1.2.7/aoede-mac-universal.tar.gz' }
] } };
assert.equal(availableUpdate(data).version, '1.2.0');
const sameNameBuild = { release: { downloads: [{ ...data.release.downloads[0], version: '1.1.9', version_code: 16 }] } };
assert.equal(availableUpdate(sameNameBuild, { kind: 'android-native', version: '1.1.9', versionCode: 15 }).version_code, 16);
assert.equal(availableUpdate(sameNameBuild, { kind: 'android-native', version: '1.1.8', versionCode: 17 }), null); assert.equal(availableUpdate(data, mac).version, '1.2.9');
assert.equal(availableUpdate(data, { ...mac, version: '1.2.9' }), null);
assert.equal(availableUpdate(data, { ...mac, platform: 'win' }), null);
assert.equal(availableUpdate({ release: { downloads: [{ ...data.release.downloads[0], url: 'https://evil.test/a.apk' }] } }), null);
assert.equal(availableUpdate({ release: { downloads: [{ ...data.release.downloads[0], version: undefined }] } }), null);
renderClientUpdate(notice, data, true); assert.equal(notice.style.display, 'block');
notice.querySelector('[data-update-install]').onclick(); assert.deepEqual(messages, ['update']);
notice.querySelector('[data-update-dismiss]').onclick(); renderClientUpdate(notice, data, true); assert.equal(notice.style.display, 'none');
renderClientUpdate(notice, data); assert.equal(notice.style.display, 'block'); // About remains available after dismissing.
window.AOEDE_CLIENT = null; renderClientUpdate(notice, data); notice.querySelector('[data-update-install]').onclick();
assert.deepEqual(urls, [data.release.downloads[0].url]);
window.AOEDE_CLIENT = { updater: 1 }; initClientUpdates(); assert.equal(messages.at(-1), 'check-update');
events.get('aoede-client-update')({ detail: data }); assert.equal(notice.style.display, 'none'); // Still snoozed.

const nativeChecks = () => messages.filter(message => message === 'check-update').length;
const beforeCheck = nativeChecks();
context.fetch = async () => ({ ok: true, headers: { get: () => 'application/json' }, json: async () => data });
events.get('online')(); for (let i = 0; i < 10; i++) await Promise.resolve();
assert.equal(nativeChecks(), beforeCheck); // Shared backend cache avoids a GitHub request per device.
context.fetch = async () => ({ ok: false, status: 404 });
events.get('online')(); for (let i = 0; i < 10; i++) await Promise.resolve();
assert.equal(nativeChecks(), beforeCheck + 1); // An older backend still supports native checks.
assert.equal(availableUpdate({ release: { downloads: {} } }), null);
assert.equal(availableUpdate({ release: { downloads: [null] } }), null);

// A direct updater link takes precedence over a previously selected System tab.
const tabs = ['system', 'updates'].map(value => ({ ...element(), dataset: { settingsTab: value }, setAttribute(key, value) { this[key] = value; } }));
const panels = tabs.map(tab => ({ ...element(), dataset: { settingsPanel: tab.dataset.settingsTab }, setAttribute() {} }));
const root = { dataset: { settingsTabs: 'system' }, querySelectorAll: key => key === '[data-settings-tab]' ? tabs : panels, addEventListener() {} };
ui.namespace.initSettingsTabs({ querySelectorAll: () => [root] });
assert.equal(tabs[1]['aria-selected'], 'true'); assert.equal(panels[1].hidden, false);

const server = element(); fields.set('update-available-toast', server);
for (const id of ['update-toast-message', 'update-toast-dismiss', 'update-toast-open']) fields.set(id, element());
context.fetch = async () => ({ status: 200, ok: true, json: async () => ({ update_available: true, remote_full_commit: 'abcdef', remote_version: '1.2.8' }) });
const admin = new vm.SourceTextModule(await readFile('Aoede/assets/js/modules/admin_system.js', 'utf8'), { context });
await admin.link(name => {
  if (name === './ui.js') return ui;
  const exported = name.includes('admin_downloads') ? 'initDownloadManager' : name.includes('system_monitor') ? 'initSystemMonitor' : 'initUpdateProgress';
  return new vm.SyntheticModule([exported], function () { this.setExport(exported, () => {}); }, { context });
});
await admin.evaluate(); for (let i = 0; i < 10; i++) await Promise.resolve();
assert.equal(server.style.display, 'block'); fields.get('update-toast-open').onclick();
assert.equal(window.location.href, '/admin/system?tab=updates');
console.log('Client updates: channel/version matching, untrusted metadata, storage failure, native/manual actions, snooze and updater deep link passed');
