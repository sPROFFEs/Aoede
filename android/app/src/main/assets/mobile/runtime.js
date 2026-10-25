(() => {
  if (window.__aoedeApplyViewport) { window.__aoedeApplyViewport(window.AOEDE_VIEWPORT); return; }
  window.AOEDE_ANDROID = true;
  window.__aoedeApplyViewport = (viewport = {}) => {
    const root = document.documentElement;
    for (const side of ['top', 'right', 'bottom', 'left']) {
      const value = Number(viewport[side]);
      if (Number.isFinite(value) && value >= 0) root.style.setProperty(`--safe-area-${side}`, `${value}px`);
    }
    root.dataset.keyboard = viewport.keyboard ? 'open' : 'closed';
    window.AOEDE_VIEWPORT = viewport;
  };
  window.__aoedeApplyViewport(window.AOEDE_VIEWPORT);
  // Dismiss overlays before leaving the current page, using their own close paths.
  window.__aoedeBack = () => {
    const modal = document.querySelector('#modal-container .modal');
    if (modal && typeof window.closeModal === 'function') { window.closeModal(); return true; }
    const close = document.querySelector('.modal-overlay:not(.hidden) .modal-close');
    if (close) { close.click(); return true; }
    const fullscreen = document.querySelector('.fullscreen-player.open');
    if (fullscreen) {
      fullscreen.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      return true;
    }
    const menu = document.querySelector('details[open]');
    if (menu) { menu.open = false; menu.querySelector('summary')?.focus(); return true; }
    const storyClose = document.querySelector('#story-close');
    if (storyClose) { storyClose.click(); return true; }
    return false;
  };
  document.addEventListener('submit', event => {
    if (event.target?.getAttribute('action') === '/logout') localStorage.removeItem('aoede.android.bootstrap');
  }, true);
  const ready = () => {
    const root = document.documentElement;
    root.dataset.client = 'android';
    let lastDark;
    const appearance = () => {
      const text = getComputedStyle(document.body).color.match(/[\d.]+/g) || [];
      const dark = !!document.querySelector('.fullscreen-player.open') || Number(text[0]) > 128;
      if (dark === lastDark) return;
      lastDark = dark;
      window.AoedeHost?.postMessage(JSON.stringify({ type: 'appearance', dark }));
    };
    new MutationObserver(appearance).observe(root, { attributes: true, attributeFilter: ['data-theme'] });
    const fullscreen = document.getElementById('fullscreen-player');
    if (fullscreen) new MutationObserver(appearance).observe(fullscreen, { attributes: true, attributeFilter: ['class'] });
    window.matchMedia('(prefers-color-scheme: dark)').addEventListener?.('change', appearance);
    appearance();
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
      const appearancePanel = document.querySelector('#main-view [data-settings-panel="appearance"] .user-settings-panel-body');
      if (appearancePanel && !appearancePanel.querySelector('.native-island-settings')) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'user-settings-button-secondary native-island-settings';
        button.textContent = ({ en: 'Dynamic Island · this Android device', es: 'Dynamic Island · este dispositivo Android', de: 'Dynamic Island · dieses Android-Gerät' })[root.lang] || 'Dynamic Island · this Android device';
        button.onclick = () => window.AoedeHost?.postMessage('island-settings');
        appearancePanel.append(button);
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
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', ready, { once: true });
  else ready();
})();
