import { escHtml, refreshIcons, t } from './ui.js';

const versionPattern = /^\d+\.\d+\.\d+$/;
const officialDownload =
  /^https:\/\/github\.com\/sPROFFEs\/Aoede\/releases\/download\/[A-Za-z0-9][A-Za-z0-9._%+-]*\/aoede-[A-Za-z0-9.-]+$/;
let latest = null;
let checking = false;
let checkedAt = 0;
const dismissed = new Map();

export function newerVersion(candidate, installed) {
  if (!versionPattern.test(candidate) || !versionPattern.test(installed)) return false;
  const left = candidate.split('.').map(Number);
  const right = installed.split('.').map(Number);
  for (let i = 0; i < 3; i++) if (left[i] !== right[i]) return left[i] > right[i];
  return false;
}

export function currentClient(ua = navigator.userAgent) {
  const android = ua.match(/AoedeAndroid\/(\d+\.\d+\.\d+)/);
  if (android)
    return {
      kind: newerVersion('1.1.0', android[1]) ? 'android-wrapper' : 'android-native',
      version: android[1],
      versionCode: window.AOEDE_CLIENT?.version === android[1] ? window.AOEDE_CLIENT.versionCode : undefined,
      platform: 'android'
    };
  if (!ua.includes('AoedeDesktop')) return null;
  const platform = /Windows/i.test(ua)
    ? 'win'
    : /Macintosh|Mac OS/i.test(ua)
      ? 'mac'
      : /Linux/i.test(ua)
        ? 'linux'
        : null;
  return {
    kind: 'desktop',
    version: ua.match(/AoedeDesktop\/(\d+\.\d+\.\d+)/)?.[1] || '',
    platform,
    arch:
      platform === 'mac'
        ? 'universal'
        : /aarch64|arm64/i.test(ua)
          ? 'arm64'
          : /x86_64|x64|Win64|amd64/i.test(ua)
            ? 'x64'
            : null
  };
}

export function availableUpdate(data, client = currentClient()) {
  if (!client) return null;
  const files = Array.isArray(data?.release?.downloads) ? data.release.downloads : [];
  return (
    files.find(
      (file) =>
        file &&
        officialDownload.test(file.url) &&
        versionPattern.test(file.version) &&
        (client.kind.startsWith('android')
          ? file.client === 'android-native' && file.name === 'aoede-android.apk'
          : file.client === 'desktop' && file.platform === client.platform && file.arch === client.arch) &&
        (!client.version ||
          (Number.isInteger(client.versionCode) && Number.isInteger(file.version_code)
            ? file.version_code > client.versionCode
            : newerVersion(file.version, client.version)))
    ) || null
  );
}

export function renderClientUpdate(container, data = latest, dismissible = false) {
  if (!container) return;
  const client = currentClient();
  const update = availableUpdate(data, client);
  container.replaceChildren();
  container.style.display = 'none';
  if (!update) return;
  const key = `aoede-client-update:${client.kind}:${update.version}:${update.version_code || ''}`;
  let until = dismissed.get(key) || 0;
  try {
    until = Math.max(until, Number(localStorage.getItem(key)) || 0);
  } catch {}
  if (dismissible && until > Date.now()) return;
  const native =
    client.kind === 'android-native' && window.AOEDE_CLIENT?.updater === 1 && window.AoedeHost?.postMessage;
  const sameVersion = client.version === update.version;
  const installedLabel = sameVersion ? `${client.version} (${client.versionCode})` : client.version;
  const availableLabel = sameVersion ? `${update.version} (${update.version_code})` : update.version;
  const title = t(
    client.version ? 'updates.client_title' : 'updates.client_unknown',
    client.version ? 'App update available' : 'Check your app version'
  );
  container.innerHTML = `<section class="update-toast-card" aria-label="${escHtml(title)}">
    <div class="update-toast-head"><div class="update-toast-icon"><i data-lucide="download-cloud"></i></div><div class="update-toast-copy">
    <strong class="update-toast-title">${escHtml(title)}</strong><div class="update-toast-message" role="status">${escHtml(t('updates.client_versions', '{client}: installed {installed} · available {available}', { client: client.kind === 'desktop' ? 'Aoede Desktop' : client.kind === 'android-wrapper' ? 'Aoede Android wrapper → Aoede Android' : 'Aoede Android', installed: installedLabel || t('updates.unknown', 'unknown'), available: availableLabel }))}</div>
    <small class="client-update-details">${escHtml(t(native ? 'updates.android_confirm' : 'updates.manual_install', native ? 'Download and verify the APK, then confirm installation in Android.' : 'Download the new version and install it on this device.'))}</small></div></div>
    <div class="update-toast-actions">${dismissible ? `<button type="button" class="update-toast-btn" data-update-dismiss>${escHtml(t('updates.later', 'Remind me tomorrow'))}</button>` : ''}
    <button type="button" class="update-toast-btn update-toast-btn-primary" data-update-install>${escHtml(t(native ? 'updates.install' : 'updates.download', native ? 'Update app' : 'Download update'))}</button></div></section>`;
  container.style.display = 'block';
  container.querySelector('[data-update-install]').onclick = () => {
    if (native) window.AoedeHost.postMessage('update');
    else window.location.assign(update.url);
  };
  const dismiss = container.querySelector('[data-update-dismiss]');
  if (dismiss)
    dismiss.onclick = () => {
      const next = Date.now() + 86400000;
      dismissed.set(key, next);
      try {
        localStorage.setItem(key, String(next));
      } catch {}
      container.style.display = 'none';
    };
  refreshIcons(container);
}

export function initClientUpdates() {
  if (!currentClient()) return;
  const show = (data) => {
    latest = data;
    renderClientUpdate(document.getElementById('client-update-notice'), data, true);
  };
  window.addEventListener('aoede-client-update', (event) => show(event.detail));
  async function check() {
    if (document.hidden || checking || Date.now() - checkedAt < 300000) return;
    checkedAt = Date.now();
    checking = true;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch('/api/about', { cache: 'no-store', signal: controller.signal });
      if (response.ok && response.headers.get('content-type')?.includes('application/json')) {
        const data = await response.json();
        const known =
          Array.isArray(data?.release?.downloads) &&
          data.release.downloads.some((file) => file?.client === 'android-native' && versionPattern.test(file.version));
        if (window.AOEDE_CLIENT?.updater !== 1 || known) {
          show(data);
          return;
        }
      }
    } catch {
      checkedAt = Date.now() - 240000;
    } finally {
      clearTimeout(timeout);
      checking = false;
    }
    // Older backends cannot describe client builds. The installed Android updater
    // can check the official release without depending on the music server.
    if (window.AOEDE_CLIENT?.updater === 1) window.AoedeHost?.postMessage('check-update');
  }
  if (window.__aoedeClientRelease) show(window.__aoedeClientRelease);
  check();
  window.addEventListener('online', () => {
    checkedAt = 0;
    check();
  });
  document.addEventListener('visibilitychange', check);
  window.setInterval(check, 300000);
}
