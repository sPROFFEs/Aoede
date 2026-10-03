const STORAGE_KEY = 'aoede_server_url';
const setupView = document.getElementById('setup-view');
const setupForm = document.getElementById('setup-form');
const serverInput = document.getElementById('server-url');
const setupError = document.getElementById('setup-error');
let connecting = false;

function showSetup(error = '') {
  setupView.style.display = 'flex';
  setupError.textContent = error;
  setupError.hidden = !error;
  serverInput.focus();
}

async function loadServer(value) {
  if (connecting) return;
  connecting = true;
  try {
    let formatted = value.trim();
    if (!/^[a-z][a-z\d+.-]*:/i.test(formatted)) formatted = 'https://' + formatted;
    const url = new URL(formatted);
    if (!['http:', 'https:'].includes(url.protocol) || !url.hostname || url.username || url.password) {
      throw new Error('Enter an HTTP or HTTPS server URL without embedded credentials.');
    }
    if (url.search || url.hash) throw new Error('Enter the server address without a query or fragment.');
    formatted = url.href.replace(/\/+$/, '');
    serverInput.value = formatted;
    let saved = false;
    try {
      await Neutralino.storage.setData(STORAGE_KEY, formatted);
      saved = true;
    } catch (_) { /* Browser storage remains a fallback when the native store is unavailable. */ }
    try {
      localStorage.setItem(STORAGE_KEY, formatted);
      saved = true;
    } catch (_) {}
    if (!saved) throw new Error('Cannot save the server address. Check the app data directory permissions.');
    window.location.replace(formatted);
  } catch (error) {
    showSetup(error.message || 'Could not connect to this server.');
  } finally {
    connecting = false;
  }
}

async function initApp() {
  try { Neutralino.init(); } catch (_) {}
  let savedUrl;
  try { savedUrl = await Neutralino.storage.getData(STORAGE_KEY); } catch (_) {}
  if (!savedUrl && typeof NL_PATH !== 'undefined') {
    // Earlier packages stored preferences inside the install directory.
    try { savedUrl = await Neutralino.filesystem.readFile(`${NL_PATH}/.storage/${STORAGE_KEY}.neustorage`); } catch (_) {}
  }
  if (!savedUrl) {
    try { savedUrl = localStorage.getItem(STORAGE_KEY); } catch (_) {}
  }
  if (typeof savedUrl === 'string' && savedUrl.trim()) {
    serverInput.value = savedUrl;
    // --setup provides recovery even when the saved server is no longer reachable.
    if (typeof NL_ARGS !== 'undefined' && NL_ARGS.includes('--setup')) showSetup();
    else await loadServer(savedUrl);
  } else showSetup();
}

setupForm.addEventListener('submit', (event) => {
  event.preventDefault();
  if (serverInput.value) loadServer(serverInput.value);
});
initApp();
