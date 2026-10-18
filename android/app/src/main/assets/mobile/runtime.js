(() => {
  window.AOEDE_ANDROID = true;
  document.addEventListener('submit', event => {
    if (event.target?.getAttribute('action') === '/logout') localStorage.removeItem('aoede.android.bootstrap');
  }, true);
  document.addEventListener('DOMContentLoaded', () => {
    const root = document.documentElement;
    root.dataset.client = 'android';
    const labels = { en: 'Configure server', es: 'Configurar servidor', de: 'Server konfigurieren' };
    const settings = document.createElement('button');
    settings.type = 'button';
    settings.className = 'user-menu-item native-server-settings';
    settings.textContent = labels[root.lang] || labels.en;
    settings.onclick = () => window.AoedeHost?.postMessage('settings');
    document.querySelector('.user-menu-panel')?.append(settings);
    // Mobile uses settings_user instead of the desktop profile dropdown.
    const insertSettings = () => {
      const target = document.querySelector('#main-view .user-settings-header');
      if (target && !target.querySelector('.native-server-settings')) {
        const button = settings.cloneNode(true);
        button.onclick = settings.onclick;
        target.append(button);
      }
    };
    new MutationObserver(insertSettings).observe(document.getElementById('main-view') || document.body, { childList: true, subtree: true });
    insertSettings();
    const percent = Math.max(0, Math.min(100, window.AOEDE_NATIVE_POOL || 0));
    const value = document.querySelector('.quota-label span:last-child');
    const bar = document.querySelector('.quota-bar-fill');
    if (value) value.textContent = `${percent}%`;
    if (bar) { bar.style.width = `${percent}%`; if (percent > 90) bar.style.background = '#ef4444'; }
    // The APK already is installed; suppress the website's install promotion.
    document.getElementById('pwa-banner')?.remove();
    try { localStorage.setItem('aoede_pwa_dismissed', '1'); } catch (_) { /* Optional storage. */ }
  }, { once: true });
})();
