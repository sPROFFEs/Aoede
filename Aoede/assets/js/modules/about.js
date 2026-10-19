import { closeModal, escHtml, focusModal, refreshIcons } from './ui.js';

const releases = 'https://github.com/sPROFFEs/Aoede/releases';
const platforms = { android: 'Android', mac: 'macOS', win: 'Windows', linux: 'Linux' };
const external = 'target="_blank" rel="noopener noreferrer"';

export async function openAbout() {
  closeModal();
  const container = document.getElementById('modal-container');
  container.innerHTML = `<div class="modal-overlay about-overlay"><section class="modal about-panel" role="dialog" aria-modal="true" aria-labelledby="about-title" lang="en">
    <header class="about-header"><div><span class="about-eyebrow">MADE FOR YOUR MUSIC</span><h2 id="about-title">About &amp; updates</h2></div><button type="button" class="modal-close" aria-label="Close"><i data-lucide="x"></i></button></header>
    <div class="about-brand"><img src="/assets/apple-touch-icon.png" alt="" width="64" height="64"><div><strong>Aoede</strong><div id="about-version">Your music. Your server.</div></div></div>
    <div class="about-content" aria-live="polite" aria-busy="true"><p class="about-status" role="status">Checking the latest release…</p></div>
    <footer class="about-footer"><span data-about-checked>Release information from GitHub</span><a href="${releases}" ${external}>All releases <i data-lucide="arrow-up-right"></i></a></footer>
  </section></div>`;
  const panel = container.querySelector('.about-panel');
  container.querySelector('.modal-close').onclick = closeModal;
  container.firstElementChild.onclick = (event) => {
    if (event.target === container.firstElementChild) closeModal();
  };
  refreshIcons(panel);
  focusModal();
  const content = panel.querySelector('.about-content');
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 15000);
  try {
    const response = await fetch('/api/about', { cache: 'no-store', signal: controller.signal });
    if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) throw new Error();
    const data = await response.json();
    if (!panel.isConnected) return;
    panel.querySelector('#about-version').textContent = `Server ${data.current.display_version}`;
    if (!data.release) throw new Error();
    const release = data.release;
    // Only link to the official repository, even if a backend returns malformed data.
    const safeLink = (url) =>
      /^https:\/\/github\.com\/sPROFFEs\/Aoede\/releases\/(?:download\/[A-Za-z0-9][A-Za-z0-9._%+-]*\/[A-Za-z0-9][A-Za-z0-9.-]*|tag\/[A-Za-z0-9][A-Za-z0-9._%+-]*)$/.test(
        url
      );
    const downloads = release.downloads.filter((item) => platforms[item.platform] && safeLink(item.url));
    content.innerHTML = `${data.stale ? '<p class="about-status" role="status">GitHub is unavailable. Showing the last successful check.</p>' : ''}
      <section class="about-release"><div class="about-section-heading"><h3>What’s new</h3><span class="about-tag">${escHtml(release.tag)}</span></div><h4>${escHtml(release.title)}</h4><p>${escHtml(release.summary || 'See the release notes for the latest changes.')}</p>${safeLink(release.url) ? `<a href="${escHtml(release.url)}" ${external}>Read release notes <i data-lucide="arrow-up-right"></i></a>` : ''}</section>
      <section class="about-downloads" aria-labelledby="about-downloads-title"><h3 id="about-downloads-title">Get Aoede</h3><p>Choose your platform. Downloads follow the latest published release.</p>
        <div class="about-tabs" role="tablist" aria-label="Download platform">${Object.entries(platforms)
          .map(
            ([id, name]) =>
              `<button type="button" role="tab" id="about-tab-${id}" aria-controls="about-files-${id}" data-platform="${id}">${name}</button>`
          )
          .join('')}</div>
        ${Object.keys(platforms)
          .map(
            (id) =>
              `<div role="tabpanel" id="about-files-${id}" aria-labelledby="about-tab-${id}" tabindex="0" hidden>${
                downloads
                  .filter((item) => item.platform === id)
                  .map(
                    (item) =>
                      `<a class="about-file" href="${escHtml(item.url)}" ${external}><i data-lucide="${item.name.endsWith('.apk') ? 'smartphone' : 'package'}"></i><span><strong>${escHtml(item.label)}</strong><small>${escHtml(item.name)}</small></span><i data-lucide="download"></i></a>`
                  )
                  .join('') ||
                '<p class="about-status">No package is published for this platform yet. Check All releases for earlier builds.</p>'
              }${id === 'android' ? '<small class="about-footnote">The wrapper is a backup client. Android clients share a package ID and cannot be installed side by side.</small>' : ''}</div>`
          )
          .join('')}
      </section>`;
    const tabs = Array.from(content.querySelectorAll('[role="tab"]'));
    const select = (tab) => {
      tabs.forEach((item) => {
        const selected = item === tab;
        item.setAttribute('aria-selected', String(selected));
        item.tabIndex = selected ? 0 : -1;
        content.querySelector(`#${item.getAttribute('aria-controls')}`).hidden = !selected;
      });
    };
    const platform = /android/i.test(navigator.userAgent)
      ? 'android'
      : /mac/i.test(navigator.platform)
        ? 'mac'
        : /win/i.test(navigator.platform)
          ? 'win'
          : 'linux';
    select(tabs.find((tab) => tab.dataset.platform === platform));
    tabs.forEach((tab, index) => {
      tab.onclick = () => select(tab);
      tab.onkeydown = (event) => {
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
        event.preventDefault();
        const next =
          event.key === 'Home'
            ? tabs[0]
            : event.key === 'End'
              ? tabs[tabs.length - 1]
              : tabs[(index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length];
        select(next);
        next.focus();
      };
    });
    const checked = new Date(data.checked_at);
    if (Number.isFinite(checked.getTime()))
      panel.querySelector('[data-about-checked]').textContent =
        `Checked ${checked.toLocaleString('en', { dateStyle: 'medium', timeStyle: 'short' })} · Cached up to 5 min`;
    refreshIcons(panel);
  } catch {
    if (panel.isConnected)
      content.innerHTML =
        '<p class="about-status" role="status">Release information is unavailable. Check your connection and that your server is up to date, or visit All releases for downloads.</p>';
  } finally {
    clearTimeout(timeout);
    content.setAttribute('aria-busy', 'false');
  }
}
