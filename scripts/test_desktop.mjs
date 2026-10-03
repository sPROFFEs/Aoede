// Run: node scripts/test_desktop.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = await readFile(new URL('../desktop/resources/js/main.js', import.meta.url), 'utf8');
function launch({ nativeRead = '', legacyRead = '', browserRead = '', failWrite = false, failBrowser = false, runtime = false, args = [] } = {}) {
  const fields = Object.fromEntries(['setup-view', 'setup-form', 'server-url', 'setup-error'].map(id =>
    [id, { value: '', style: {}, focus() {}, addEventListener() {} }]));
  const locations = [];
  const writes = [];
  let finishWrite;
  const context = vm.createContext({
    URL, console, NL_ARGS: args, NL_PATH: '/app',
    document: { getElementById: id => fields[id] },
    window: { location: { replace(url) { locations.push(url); } } },
    localStorage: {
      getItem() { if (failBrowser) throw new Error('Storage disabled'); return browserRead; },
      setItem() { if (failBrowser) throw new Error('Storage disabled'); }
    },
    Neutralino: { init() {}, filesystem: { async readFile(path) {
      assert.equal(path, '/app/.storage/aoede_server_url.neustorage');
      return legacyRead;
    } }, storage: {
      async getData() { if (nativeRead instanceof Error) throw nativeRead; return nativeRead; },
      setData(key, url) {
        writes.push({ key, url });
        if (failWrite) return Promise.reject(new Error('Write denied'));
        return new Promise(resolve => { finishWrite = resolve; });
      }
    } }
  });
  if (runtime) context.NL_APPID = 'com.aoede.desktop';
  vm.runInContext(source, context);
  return { context, fields, locations, writes, finish: () => finishWrite() };
}
const flush = async () => { for (let i = 0; i < 6; i++) await Promise.resolve(); };

const normal = launch();
await flush();
const pending = normal.context.loadServer('https://EXAMPLE.com:8443/aoede///');
assert.equal(normal.locations.length, 0); // Do not navigate while the native write is still in flight.
assert.equal(normal.writes[0].url, 'https://example.com:8443/aoede');
await normal.context.loadServer('https://other.example'); // Ignore a duplicate Connect while saving.
assert.equal(normal.writes.length, 1);
normal.finish();
await pending;
assert.deepEqual(normal.locations, ['https://example.com:8443/aoede']);

for (const url of ['javascript:alert(1)', 'file:///x', 'https://user:secret@example.com', 'https://example.com:70000', 'https://example.com?token=x']) {
  const p = launch();
  await flush();
  await p.context.loadServer(url);
  assert.equal(p.writes.length, 0);
  assert.equal(p.locations.length, 0);
  assert.equal(p.fields['setup-error'].hidden, false);
}
const fallback = launch({ failWrite: true });
await flush();
await fallback.context.loadServer('https://example.com');
assert.deepEqual(fallback.locations, ['https://example.com']);
const denied = launch({ nativeRead: new Error('Missing'), failWrite: true, failBrowser: true });
await flush();
await denied.context.loadServer('https://example.com');
assert.equal(denied.locations.length, 0);
assert.match(denied.fields['setup-error'].textContent, /Cannot save/);
const nativeDenied = launch({ failWrite: true, runtime: true });
await flush();
await nativeDenied.context.loadServer('https://example.com');
assert.equal(nativeDenied.locations.length, 0); // An ephemeral localhost origin cannot replace native persistence.
assert.match(nativeDenied.fields['setup-error'].textContent, /Cannot save/);
const recovery = launch({ nativeRead: 'https://offline.example', args: ['aoede', '--setup'] });
await flush();
assert.equal(recovery.locations.length, 0);
assert.equal(recovery.fields['server-url'].value, 'https://offline.example');
assert.equal(recovery.fields['setup-view'].style.display, 'flex');
const migration = launch({ nativeRead: new Error('Missing'), legacyRead: 'https://old.example' });
await flush();
assert.equal(migration.writes[0].url, 'https://old.example');
assert.equal(migration.locations.length, 0);
migration.finish();
await flush();
assert.deepEqual(migration.locations, ['https://old.example']);
console.log('PASS: desktop URL validation, awaited persistence, storage fallback and setup recovery.');
