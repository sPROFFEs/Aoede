/* The entry document and every shell/module/style are installed with the APK. */
(() => {
  const CACHE = 'aoede.android.bootstrap';
  const messages = {
    en: ['Connecting to your music…', 'Retry', 'Configure server', 'Update the Aoede backend to use this Android version.', 'Could not connect. Check your server and connection.', 'The server took too long to respond.', 'The server returned an invalid startup response.'],
    es: ['Conectando con tu música…', 'Reintentar', 'Configurar servidor', 'Actualiza el backend de Aoede para utilizar esta versión de Android.', 'No se ha podido conectar. Revisa el servidor y la conexión.', 'El servidor ha tardado demasiado en responder.', 'El servidor ha devuelto una respuesta de inicio no válida.'],
    de: ['Verbindung zur Musik…', 'Erneut versuchen', 'Server konfigurieren', 'Aktualisiere den Aoede-Server für diese Android-Version.', 'Keine Verbindung. Prüfe den Server und die Verbindung.', 'Der Server hat zu lange für die Antwort benötigt.', 'Der Server hat eine ungültige Startantwort zurückgegeben.'],
  };
  const deviceLang = (navigator.language || 'en').split('-')[0];
  const labels = messages[deviceLang] || messages.en;
  document.getElementById('boot-message').textContent = labels[0];
  document.getElementById('boot-retry').textContent = labels[1];
  document.getElementById('boot-settings').textContent = labels[2];
  document.getElementById('boot-retry').onclick = () => location.reload();
  document.getElementById('boot-settings').onclick = () => window.AoedeHost?.postMessage('settings');

  function valid(data) {
    return data?.protocol === 1 && /^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$/.test(data.username) &&
      typeof data.is_admin === 'boolean' && ['classic', 'glass', 'vitrine'].includes(data.theme);
  }

  async function start() {
    // Migrate the old online wrapper without clearing cookies, downloads or IndexedDB.
    if (navigator.serviceWorker) {
      const registrations = await navigator.serviceWorker.getRegistrations();
      await Promise.all(registrations.map(r => r.unregister()));
      if (navigator.serviceWorker.controller && !sessionStorage.getItem('aoede.android.sw-migrated')) {
        sessionStorage.setItem('aoede.android.sw-migrated', '1');
        location.reload();
        return;
      }
    }
    let data;
    let offline = false;
    const login = location.pathname === '/login';
    if (login) localStorage.removeItem(CACHE);
    else {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), 6000);
      let response;
      try {
        if (navigator.onLine === false) throw new TypeError('Offline');
        response = await fetch('/api/mobile/bootstrap', { credentials: 'same-origin', cache: 'no-store', signal: controller.signal });
        if (response.status === 401 || response.status === 403) {
          localStorage.removeItem(CACHE);
          location.replace('/login');
          return;
        }
        if (response.status === 404 || response.status === 426) throw new Error('compatibility');
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        data = await response.json();
        if (!valid(data)) throw new Error('compatibility');
        localStorage.setItem(CACHE, JSON.stringify(data));
      } catch (error) {
        if (response && error.name !== 'AbortError' && !(error instanceof TypeError)) throw error;
        let cached;
        try { cached = JSON.parse(localStorage.getItem(CACHE) || 'null'); } catch (_) { /* Ignore corrupt cache. */ }
        if (!valid(cached)) throw error;
        data = cached;
        offline = true;
      } finally { clearTimeout(timer); }
    }
    const cookieLang = document.cookie.match(/(?:^|;\s*)lang=(en|es|de)(?:;|$)/)?.[1];
    const lang = ['en', 'es', 'de'].includes(data?.language) ? data.language : (cookieLang || (messages[deviceLang] ? deviceLang : 'en'));
    // A new document preserves document-start media hooks and normal module loading.
    if (!window.AoedeHost) throw new Error('webview');
    const loginError = login ? new URLSearchParams(location.search).get('error')?.slice(0, 512) || '' : '';
    window.AoedeHost.postMessage(JSON.stringify({ type: 'render', data, language: lang, offline, login, loginError, home: location.hash === '#home' }));
  }
  start().catch(error => {
    document.getElementById('boot-message').textContent = labels[error.message === 'compatibility' ? 3 : error.name === 'AbortError' ? 5 : error.name === 'SyntaxError' ? 6 : 4];
    if (/^HTTP \d{3}$/.test(error.message)) document.getElementById('boot-message').textContent += ` (/api/mobile/bootstrap · ${error.message})`;
    document.getElementById('boot-retry').hidden = false;
    document.getElementById('boot-settings').hidden = false;
  });
})();
