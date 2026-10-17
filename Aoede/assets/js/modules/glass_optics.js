/** Refract only Aoede's artwork layer; never capture or distort page content. */
const NS = 'http://www.w3.org/2000/svg';
const surfaces = new Map();
let observer;
let serial = 0;
const SELECTOR =
  '.top-bar, .m-topbar, .m-bottom-nav, .player-footer, .modal, .monitor-modal, .auth-browser-dialog, .user-menu-panel, .track-context-menu, .atp-flyout, .create-menu, .toast-msg, .fs-minimize, .fs-more summary';

// Rounded-rectangle distance and outward normal, measured in CSS pixels.
function lensPixel(x, y, width, height, radius, band) {
  const dx = x - width / 2,
    dy = y - height / 2;
  const qx = Math.abs(dx) - width / 2 + radius,
    qy = Math.abs(dy) - height / 2 + radius;
  const ox = Math.max(qx, 0),
    oy = Math.max(qy, 0),
    length = Math.hypot(ox, oy);
  const distance = length + Math.min(Math.max(qx, qy), 0) - radius;
  if (distance > 0 || distance < -band) return [128, 128, 0];
  const nx = length ? ox / length : qx > qy ? 1 : 0;
  const ny = length ? oy / length : qx > qy ? 0 : 1;
  const edge = 1 + distance / band;
  const bend = 1 - Math.sqrt(Math.max(0, 1 - edge * edge));
  return [
    128 + Math.sign(dx) * nx * bend * 110,
    128 + Math.sign(dy) * ny * bend * 110,
    Math.round(Math.sin(edge * Math.PI) * 190)
  ];
}

function svg(name, attrs = {}) {
  const node = document.createElementNS(NS, name);
  Object.entries(attrs).forEach(([key, value]) => node.setAttribute(key, value));
  return node;
}

function update(surface) {
  const entry = surfaces.get(surface);
  if (!entry || !surface.isConnected) return;
  const rect = surface.getBoundingClientRect();
  if (rect.width < 1 || rect.height < 1) return;
  const radiusText = window.getComputedStyle(surface).borderTopLeftRadius;
  const radius = Math.min(
    rect.width / 2,
    rect.height / 2,
    radiusText.includes('%') ? Math.min(rect.width, rect.height) / 2 : parseFloat(radiusText) || 0
  );
  const key = [Math.round(rect.width), Math.round(rect.height), Math.round(radius)].join(':');
  if (entry.key === key) return;
  entry.key = key;
  // ponytail: one bounded map per visible surface; no animation loop or DOM screenshots.
  const scale = Math.min(1, 384 / Math.max(rect.width, rect.height));
  const width = Math.max(2, Math.round(rect.width * scale)),
    height = Math.max(2, Math.round(rect.height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const pixels = ctx.createImageData(width, height);
  const mask = ctx.createImageData(width, height);
  const band = Math.min(18, Math.max(5, Math.min(rect.width, rect.height) * 0.2));
  for (let y = 0; y < height; y++)
    for (let x = 0; x < width; x++) {
      const [r, g, alpha] = lensPixel((x + 0.5) / scale, (y + 0.5) / scale, rect.width, rect.height, radius, band);
      const i = (y * width + x) * 4;
      pixels.data.set([r, g, 128, 255], i);
      mask.data.set([255, 255, 255, alpha], i);
    }
  ctx.putImageData(pixels, 0, 0);
  entry.map.setAttribute('href', canvas.toDataURL());
  ctx.putImageData(mask, 0, 0);
  entry.mask.setAttribute('href', canvas.toDataURL());
  entry.displace.setAttribute('scale', String(band * 2));
}

export function refreshGlassOptics(scope = document) {
  const enabled =
    document.documentElement.dataset.theme === 'vitrine' &&
    window.ResizeObserver &&
    !window.matchMedia('(prefers-reduced-transparency: reduce), (prefers-contrast: more), (forced-colors: active)')
      .matches;
  for (const [surface, entry] of surfaces) {
    if (enabled && surface.isConnected) continue;
    observer?.unobserve(surface);
    entry.layer.remove();
    entry.defs.remove();
    surface.classList.remove('glass-optic-surface', 'glass-optic-position');
    surfaces.delete(surface);
  }
  if (!enabled) return;
  observer ||= new window.ResizeObserver((entries) => entries.forEach((entry) => update(entry.target)));
  // Scope icon/view refreshes reuse this pass, without a document mutation observer.
  for (const surface of scope.querySelectorAll(SELECTOR)) {
    if (surfaces.has(surface) || surface.closest('.player-hidden') || !surface.getClientRects().length) continue;
    const defs = svg('svg', { width: '0', height: '0', 'aria-hidden': 'true', class: 'glass-optic-defs' });
    const id = `aoede-lens-${++serial}`;
    const filter = svg('filter', {
      id,
      x: '0',
      y: '0',
      width: '100%',
      height: '100%',
      'color-interpolation-filters': 'sRGB'
    });
    const map = svg('feImage', {
      result: 'bend',
      preserveAspectRatio: 'none',
      x: '0',
      y: '0',
      width: '100%',
      height: '100%'
    });
    const displace = svg('feDisplacementMap', {
      in: 'SourceGraphic',
      in2: 'bend',
      xChannelSelector: 'R',
      yChannelSelector: 'G',
      result: 'refracted'
    });
    const mask = svg('feImage', {
      result: 'rim',
      preserveAspectRatio: 'none',
      x: '0',
      y: '0',
      width: '100%',
      height: '100%'
    });
    filter.append(map, displace, mask, svg('feComposite', { in: 'refracted', in2: 'rim', operator: 'in' }));
    defs.append(filter);
    const layer = document.createElement('span');
    layer.className = 'glass-optic-layer';
    layer.setAttribute('aria-hidden', 'true');
    layer.style.filter = `url("#${id}")`;
    if (window.getComputedStyle(surface).position === 'static') surface.classList.add('glass-optic-position');
    surface.classList.add('glass-optic-surface');
    surface.append(defs, layer);
    surfaces.set(surface, { defs, layer, map, mask, displace });
    update(surface);
    observer.observe(surface);
  }
}
