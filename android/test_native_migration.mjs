import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const source = fs.readFileSync(new URL('app/src/main/assets/mobile/native-migration.js', import.meta.url), 'utf8');
const username = 'alice';
const key = (id) => JSON.stringify([username, id]);
const collection = {id: 4, name: 'A large owned collection', tracks: Array.from({length: 1800}, (_, i) => ({id: 7, db_id: 7, title: `Recording ${i} — 音楽`, artist: 'Synthetic artist'}))};
const audio = new Blob([new Uint8Array(110000).fill(73)]);
const stores = {
  library_snapshots: new Map([[key('offline_playlists_manifest'), {payload: [{id: 4}]}], [key('playlist_4'), {payload: collection}], [JSON.stringify(['bob', 'playlist_5']), {payload: {id: 5, tracks: [{db_id: 9}]}}]]),
  tracks: new Map([[7, {status: 'complete', metadata: {db_id: 7, id: 7, title: 'Owned audio'}}], [9, {status: 'complete', metadata: {db_id: 9}}]]),
  audio: new Map([[7, {blob: audio}], [9, {blob: audio}]]),
  pending_actions: new Map([[1, {action_id: 1, username, type: 'edit_playlist', payload: {playlistId: 4, name: 'Renamed'}}], [2, {action_id: 2, username: 'bob', type: 'delete_playlist'}]])
};
const messages = [];
let closed = false;
const request = (value) => {
  const result = {result: value};
  queueMicrotask(() => result.onsuccess());
  return result;
};
const db = {
  objectStoreNames: {contains: (name) => Object.hasOwn(stores, name)},
  transaction: (name) => ({objectStore: () => ({get: (id) => request(stores[name].get(id)), getAll: () => request([...stores[name].values()])})}),
  close: () => { closed = true; }
};
const bridge = {
  postMessage: (raw) => {
    assert.ok(raw.length < 128 * 1024, 'Every message fits the native bridge limit');
    const message = JSON.parse(raw);
    assert.equal(message.nonce, 'synthetic-nonce');
    messages.push(message);
    queueMicrotask(() => bridge.onmessage({data: 'ok'}));
  }
};
await vm.runInNewContext(source.replace('__NONCE_JSON__', JSON.stringify('synthetic-nonce')).replace('__USER_JSON__', JSON.stringify(username)), {
  Blob, Uint8Array, setTimeout, clearTimeout, btoa, AoedeMigration: bridge, indexedDB: {open: () => request(db)}
});
assert.ok(closed);
assert.equal(messages.at(-1).type, 'done');
assert.deepEqual(messages.filter((m) => m.type === 'track').map((m) => m.metadata.db_id), [7]);
assert.equal(messages.filter((m) => m.type === 'chunk').reduce((size, m) => size + Buffer.from(m.data, 'base64').length, 0), audio.size);
const records = [];
let chunks = [], kind = '';
for (const message of messages) {
  if (message.type === 'record') {kind = message.kind; chunks = [];}
  if (message.type === 'record_chunk') chunks.push(Buffer.from(message.data, 'base64'));
  if (message.type === 'record_end') records.push([kind, JSON.parse(Buffer.concat(chunks).toString())]);
}
assert.deepEqual(records, [['collection', collection], ['pending', stores.pending_actions.get(1)]]);
assert.equal(stores.tracks.size, 2, 'Original audio, other accounts and pending actions remain untouched');
assert.equal(stores.pending_actions.size, 2);
console.log('Native migration: large Unicode records, bounded audio chunks, ownership and original-data retention passed.');
