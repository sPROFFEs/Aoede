// Run: node --experimental-vm-modules scripts/test_appearance.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../Aoede/assets/js/modules/appearance.js', import.meta.url), 'utf8');
const head = (await readFile(new URL('../Aoede/concierge/templates/appearance_head.html', import.meta.url), 'utf8')).split('<script>')[1].split('</script>')[0];
const storage = new Map([['aoede.theme.alice', 'glass'], ['aoede.theme.last', 'glass']]);
const properties = new Map();
const root = { dataset: { theme: 'classic', themeUser: 'alice' }, style: { setProperty: (k, v) => properties.set(k, v), removeProperty: k => properties.delete(k) } };
const options = ['classic', 'glass', 'vitrine'].map(value => ({ value, checked: false, closest: () => ({ classList: { toggle() {} } }) }));
const fieldset = { disabled: false };
const meta = { setAttribute: (_key, value) => { meta.content = value; } };
let cover;
const document = {
  documentElement: root,
  querySelector: () => meta,
  querySelectorAll: selector => selector === '[data-user-theme]' ? options : [fieldset],
  getElementById: () => cover,
  createElement: () => ({ getContext: () => ({ drawImage() { throw new Error('Cross-origin canvas'); } }) })
};
let modeListener;
const mode = { matches: false, addEventListener: (_name, fn) => { modeListener = fn; } };
const window = { USER_DATA: { username: 'alice' }, location: { href: 'https://aoede.test/portal' }, matchMedia: () => mode, localStorage: { getItem: k => storage.get(k), setItem: (k, v) => storage.set(k, v) } };
const navigator = { onLine: true };
const messages = [];
const calls = [];
let respond = async () => Response.json({ theme: 'glass', username: 'alice' });
const context = vm.createContext({ window, document, navigator, localStorage: window.localStorage, URL, AbortController, setTimeout, clearTimeout, fetch: (url, init) => { calls.push({ url, init }); return respond(); } });
const mod = new vm.SourceTextModule(source, { context });
await mod.link(() => new vm.SyntheticModule(['t', 'showToast', 'refreshIcons'], function () {
  this.setExport('t', (_key, fallback) => fallback);
  this.setExport('showToast', (message, type) => messages.push({ message, type }));
  this.setExport('refreshIcons', () => {});
}, { context }));
await mod.evaluate();
const appearance = mod.namespace;

// The authenticated server choice wins online; only the same account's cache wins offline.
vm.runInContext(head, context);
assert.equal(root.dataset.theme, 'classic');
navigator.onLine = false;
vm.runInContext(head, context);
assert.equal(root.dataset.theme, 'glass');
root.dataset.themeUser = 'bob'; root.dataset.theme = 'classic';
vm.runInContext(head, context);
assert.equal(root.dataset.theme, 'classic');
root.dataset.themeUser = ''; vm.runInContext(head, context);
assert.equal(root.dataset.theme, 'glass'); // Login remembers style only, not identity.
storage.set('aoede.theme.alice', 'vitrine'); root.dataset.themeUser = 'alice';
vm.runInContext(head, context);
assert.equal(root.dataset.theme, 'vitrine'); assert.equal(meta.content, '#f5f5f8');
root.dataset.theme = 'classic'; navigator.onLine = true;

appearance.initAppearance();
assert.equal(options[0].checked, true);
assert.equal(meta.content, '#121212');
const before = appearance.requestGeneration;
let resolve;
respond = () => new Promise(done => { resolve = done; });
const save = appearance.saveUserTheme('glass');
assert.equal(fieldset.disabled, true);
assert.equal(root.dataset.theme, 'classic'); // No false confirmation before the server commits.
appearance.syncTheme({ username: 'alice', theme: 'glass' }, before);
assert.equal(root.dataset.theme, 'classic');
await appearance.saveUserTheme('classic');
assert.equal(calls.length, 1); // Block double submissions.
resolve(Response.json({ theme: 'glass', username: 'alice' })); await save;
assert.equal(root.dataset.theme, 'glass');
assert.equal(storage.get('aoede.theme.alice'), 'glass');
assert.equal(fieldset.disabled, false);
assert.equal(options[1].checked, true);
assert.equal(calls[0].url, '/user/appearance');
assert.equal(calls[0].init.credentials, 'same-origin');
assert.deepEqual(JSON.parse(calls[0].init.body), { theme: 'glass' });
appearance.syncTheme({ username: 'alice', theme: 'classic' }, before + 1);
assert.equal(root.dataset.theme, 'glass'); // Heartbeats started before/during saving cannot undo it.
appearance.syncTheme({ username: 'bob', theme: 'classic' });
assert.equal(root.dataset.theme, 'glass');
appearance.syncTheme(null);
assert.equal(root.dataset.theme, 'glass');
appearance.syncTheme({ username: 'alice', theme: 'classic' });
assert.equal(root.dataset.theme, 'classic');

for (const response of [Response.json({ error: 'Cannot save' }, { status: 500 }), new Response('<!DOCTYPE html>', { status: 401 }), Response.json({ theme: 'glass', username: 'bob' }), Response.json({ theme: 'unknown', username: 'alice' })]) {
  respond = async () => response;
  await appearance.saveUserTheme('glass');
  assert.equal(root.dataset.theme, 'classic');
  assert.equal(fieldset.disabled, false);
  assert.equal(messages.at(-1).type, 'error');
}
respond = async () => { throw Object.assign(new Error('Timeout'), { name: 'AbortError' }); };
await appearance.saveUserTheme('glass');
assert.equal(root.dataset.theme, 'classic');
const count = calls.length;
navigator.onLine = false; await appearance.saveUserTheme('glass');
assert.equal(calls.length, count);
assert.equal(root.dataset.theme, 'classic');
assert.match(messages.at(-1).message, /Connect/);
navigator.onLine = true;
window.localStorage.setItem = window.localStorage.getItem = () => { throw new Error('Storage restricted'); };
vm.runInContext(head, context);
respond = async () => Response.json({ theme: 'glass', username: 'alice' });
await appearance.saveUserTheme('glass');
assert.equal(root.dataset.theme, 'glass');
mode.matches = true; modeListener();
assert.equal(meta.content, '#0d0e11');
cover = { src: 'https://images.test/cover.jpg', getAttribute: () => 'cover.jpg', complete: true, naturalWidth: 200 };
appearance.applyTheme('glass');
assert.match(properties.get('--square-artwork'), /cover.jpg/);
assert.equal(properties.has('--square-seed'), false); // Blocked canvas sampling keeps the default accent.
cover.src = 'javascript:bad()';
appearance.applyTheme('glass');
assert.equal(properties.has('--square-artwork'), false);
cover = undefined;
appearance.applyTheme('glass');
assert.equal(properties.has('--square-artwork'), false);
appearance.applyTheme('classic');
assert.equal(properties.has('--square-artwork'), false);
assert.equal(meta.content, '#121212');
window.matchMedia = undefined;
appearance.applyTheme('glass'); // Old wrapper storage/media APIs are optional.
await appearance.saveUserTheme('unknown');
assert.equal(root.dataset.theme, 'glass');
window.matchMedia = () => mode;
respond = async () => Response.json({ theme: 'vitrine', username: 'alice' });
await appearance.saveUserTheme('vitrine');
assert.equal(root.dataset.theme, 'vitrine'); assert.equal(options[2].checked, true);
assert.equal(options[0].checked, false); assert.equal(options[1].checked, false);
assert.equal(meta.content, '#111216');
cover = { src: 'https://images.test/vitrine.jpg', getAttribute: () => 'vitrine.jpg', complete: true, naturalWidth: 200 };
appearance.applyTheme('vitrine');
assert.match(properties.get('--square-artwork'), /vitrine.jpg/);
mode.matches = false; modeListener(); assert.equal(meta.content, '#f5f5f8');
appearance.syncTheme({ username: 'alice', theme: 'glass' });
assert.equal(root.dataset.theme, 'glass');
appearance.syncTheme({ username: 'alice', theme: 'vitrine' });
assert.equal(root.dataset.theme, 'vitrine');
appearance.applyTheme('classic'); assert.match(properties.get('--square-artwork'), /vitrine.jpg/); // Only its player uses artwork tint.
console.log('Appearance: account persistence, offline isolation, stale responses, errors and artwork checks passed.');
