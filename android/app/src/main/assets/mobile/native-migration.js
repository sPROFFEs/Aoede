(async () => {
  const nonce = __NONCE_JSON__, username = __USER_JSON__;
  const send = payload => new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Migration timeout')), 30000);
    AoedeMigration.onmessage = event => { clearTimeout(timer); resolve(event.data); };
    AoedeMigration.postMessage(JSON.stringify({ ...payload, nonce }));
  });
  const read = request => new Promise((resolve, reject) => { request.onsuccess = () => resolve(request.result); request.onerror = () => reject(request.error); });
  const chunks = async (blob, type) => {
    for (let offset = 0; offset < blob.size; offset += 48 * 1024) {
      const bytes = new Uint8Array(await blob.slice(offset, offset + 48 * 1024).arrayBuffer());
      let binary = ''; for (const value of bytes) binary += String.fromCharCode(value);
      await send({type, offset, data:btoa(binary)});
    }
  };
  const record = async (kind, value) => {
    const blob = new Blob([JSON.stringify(value)]);
    if (blob.size > 4 * 1024 * 1024) throw new Error('Record too large');
    await send({type:'record', kind, size:blob.size});
    await chunks(blob, 'record_chunk');
    await send({type:'record_end'});
  };
  try {
    const open = indexedDB.open('aoede_offline_db', 1);
    const db = await read(open);
    if (!db.objectStoreNames.contains('library_snapshots')) { await send({type:'done'}); return; }
    const snapshots = db.transaction('library_snapshots').objectStore('library_snapshots');
    const manifest = await read(snapshots.get(JSON.stringify([username, 'offline_playlists_manifest'])));
    const collections = [], allowed = new Set();
    for (const item of manifest?.payload || []) {
      const record = await read(db.transaction('library_snapshots').objectStore('library_snapshots').get(JSON.stringify([username, `playlist_${Number(item.id)}`])));
      if (!record?.payload) continue;
      collections.push(record.payload);
      for (const track of record.payload.tracks || []) { const id = Number(track.db_id || track.id); if(id > 0) allowed.add(id); }
    }
    for (const id of allowed) {
      const record = await read(db.transaction('tracks').objectStore('tracks').get(id));
      if (record?.status !== 'complete') continue;
      const audio = await read(db.transaction('audio').objectStore('audio').get(id));
      if (!(audio?.blob instanceof Blob) || !audio.blob.size) continue;
      const metadata = { ...record.metadata };
      if (record.artwork_blob instanceof Blob && record.artwork_blob.size < 2 * 1024 * 1024) {
        try {
          const bitmap = await createImageBitmap(record.artwork_blob, {resizeWidth:512, resizeHeight:512});
          const canvas = document.createElement('canvas'); canvas.width = canvas.height = 512;
          canvas.getContext('2d').drawImage(bitmap,0,0,512,512); bitmap.close();
          const cover = canvas.toDataURL('image/jpeg', .65);
          if (cover.length < 96 * 1024) metadata.offline_artwork = cover;
        } catch (_) { /* Keep the audio when artwork is unavailable. */ }
      }
      await send({type:'track', metadata, size:audio.blob.size});
      await chunks(audio.blob, 'chunk');
      await send({type:'end'});
    }
    for (const value of collections) await record('collection', value);
    const actions = await read(db.transaction('pending_actions').objectStore('pending_actions').getAll());
    for (const value of actions) if(value.username === username) await record('pending', value);
    db.close(); await send({type:'done'});
  } catch (_) { await send({type:'error'}).catch(() => {}); }
})();
