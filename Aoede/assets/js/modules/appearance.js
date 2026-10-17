/** Account theme, shared by every view; the server owns the preference. */
import * as ui from './ui.js';

export let requestGeneration = 0;
let saving = false;
let initialized = false;
const themes = new Set(['classic', 'glass', 'vitrine']);

function updateBrowserColor() {
  const theme = document.documentElement.dataset.theme;
  const dark = window.matchMedia?.('(prefers-color-scheme: dark)').matches;
  let color = '#121212';
  if (theme === 'glass') color = dark ? '#0d0e11' : '#f1f2f6';
  if (theme === 'vitrine') color = dark ? '#111216' : '#f5f5f8';
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', color);
}

export function syncThemeControls(scope = document) {
  scope.querySelectorAll('[data-user-theme]').forEach((input) => {
    input.checked = input.value === document.documentElement.dataset.theme;
    input.closest('.theme-option')?.classList.toggle('selected', input.checked);
  });
  scope.querySelectorAll('[data-theme-controls]').forEach((fieldset) => {
    fieldset.disabled = saving;
  });
}

function updateArtwork() {
  const root = document.documentElement;
  if (!['glass', 'vitrine'].includes(root.dataset.theme)) return;
  root.style.removeProperty('--square-artwork');
  root.style.removeProperty('--square-seed');
  const cover = document.getElementById('player-cover');
  if (!cover?.getAttribute('src')) return;
  try {
    const url = new URL(cover.src, window.location.href);
    if (!['http:', 'https:', 'blob:'].includes(url.protocol)) return;
    root.style.setProperty('--square-artwork', `url(${JSON.stringify(url.href)})`);
    if (!cover.complete || !cover.naturalWidth) return;
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = 8;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) return;
    context.drawImage(cover, 0, 0, 8, 8);
    const pixels = context.getImageData(0, 0, 8, 8).data;
    const rgb = [0, 0, 0];
    let count = 0;
    for (let index = 0; index < pixels.length; index += 4) {
      if (pixels[index + 3] < 128) continue;
      rgb.forEach((_, channel) => {
        rgb[channel] += pixels[index + channel];
      });
      count++;
    }
    if (count)
      root.style.setProperty('--square-seed', `rgb(${rgb.map((value) => Math.round(value / count)).join(', ')})`);
  } catch (_) {
    // Cross-origin artwork can tint the backdrop even when canvas sampling is unavailable.
  }
}

export function applyTheme(theme) {
  if (!themes.has(theme)) return;
  document.documentElement.dataset.theme = theme;
  const username = window.USER_DATA?.username || document.documentElement.dataset.themeUser;
  try {
    if (username) window.localStorage.setItem(`aoede.theme.${username}`, theme);
    window.localStorage.setItem('aoede.theme.last', theme);
  } catch (_) {
    /* Private browsing and wrappers may restrict storage. */
  }
  if (theme !== 'classic') updateArtwork();
  else {
    document.documentElement.style.removeProperty('--square-artwork');
    document.documentElement.style.removeProperty('--square-seed');
  }
  updateBrowserColor();
  syncThemeControls();
  ui.updateFullscreenPlayButton?.();
}

export function syncTheme(data, generation = requestGeneration) {
  if (!data || saving || generation !== requestGeneration || data.username !== window.USER_DATA?.username) return;
  if (themes.has(data.theme) && data.theme !== document.documentElement.dataset.theme) applyTheme(data.theme);
}

export async function saveUserTheme(theme) {
  if (saving || !themes.has(theme)) return;
  const previous = document.documentElement.dataset.theme;
  if (!navigator.onLine) {
    syncThemeControls();
    ui.showToast(ui.t('appearance.connect', 'Connect to save your theme across devices.'), 'error');
    return;
  }
  saving = true;
  requestGeneration++;
  syncThemeControls();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch('/user/appearance', {
      method: 'PATCH',
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller.signal,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ theme })
    });
    const data = await response.json().catch(() => null);
    if (!response.ok || data?.username !== window.USER_DATA?.username || !themes.has(data?.theme)) {
      throw new Error(data?.error || ui.t('appearance.failed', 'Could not save the theme. Please try again.'));
    }
    applyTheme(data.theme);
    ui.showToast(ui.t('appearance.saved', 'Theme saved for all your devices.'), 'success');
  } catch (error) {
    applyTheme(previous);
    ui.showToast(
      error.name === 'AbortError'
        ? ui.t('appearance.failed', 'Could not save the theme. Please try again.')
        : error.message,
      'error'
    );
  } finally {
    clearTimeout(timer);
    saving = false;
    requestGeneration++;
    syncThemeControls();
  }
}

export function initAppearance() {
  if (initialized) return;
  initialized = true;
  applyTheme(document.documentElement.dataset.theme || 'classic');
  const mode = window.matchMedia?.('(prefers-color-scheme: dark)');
  if (mode?.addEventListener) mode.addEventListener('change', updateBrowserColor);
  else mode?.addListener?.(updateBrowserColor);
  const cover = document.getElementById('player-cover');
  if (cover) {
    cover.addEventListener('load', updateArtwork);
    if (window.MutationObserver)
      new window.MutationObserver(updateArtwork).observe(cover, { attributes: true, attributeFilter: ['src'] });
  }
}
