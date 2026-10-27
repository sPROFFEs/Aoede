// Run: node scripts/test_ui_icons.mjs
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

class Element {
  constructor(tag, attributes = {}, children = []) {
    this.localName = tag;
    this.attrs = new Map(Object.entries(attributes));
    this.children = children;
    this.dataset = {};
    this.classList = {
      add: (...names) => this.attrs.set('class', [...new Set([...(this.attrs.get('class') || '').split(' '), ...names])].join(' ').trim()),
      remove: name => this.attrs.set('class', (this.attrs.get('class') || '').split(' ').filter(value => value !== name).join(' '))
    };
  }
  get attributes() { return [...this.attrs].map(([name, value]) => ({ name, value })); }
  getAttribute(name) { return this.attrs.get(name); }
  setAttribute(name, value) { this.attrs.set(name, String(value)); }
  hasAttribute(name) { return this.attrs.has(name); }
  replaceWith(node) { nodes.splice(nodes.indexOf(this), 1, node); replacements++; }
}
const defaults = { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor' };
const nodes = [new Element('i', { 'data-lucide': 'play', width: '32', class: 'transport', style: 'color:red' }), new Element('i', { 'data-lucide': 'search' })];
let replacements = 0;
const document = { documentElement: { dataset: { theme: 'classic' } }, querySelectorAll: () => [...nodes] };
const library = { icons: { Play: ['svg', defaults, []], Search: ['svg', defaults, []], Menu: ['svg', defaults, []] }, createElement: ([tag, attributes, children]) => new Element(tag, attributes, children) };
vm.runInNewContext(readFileSync(new URL('../Aoede/assets/js/ui_icons.js', import.meta.url), 'utf8'), { window: { lucide: library }, document });

library.createIcons({ nodes: [nodes[0]] });
assert.equal(replacements, 1);
assert.equal(nodes[1].localName, 'i'); // Scoped refresh cannot redraw the rest of the feed.
assert.equal(nodes[0].getAttribute('width'), '32');
assert.equal(nodes[0].getAttribute('style'), 'color:red');
assert.ok(nodes[0].getAttribute('class').includes('transport'));
assert.equal(nodes[0].getAttribute('aria-hidden'), 'true');
assert.equal(nodes[0].getAttribute('focusable'), 'false');
const play = nodes[0];
library.createIcons();
assert.equal(nodes[0], play); // Same SVG is retained even during legacy global refreshes.
assert.equal(replacements, 2);
for (const theme of ['vitrine', 'glass', 'classic', 'glass']) {
  document.documentElement.dataset.theme = theme;
  library.createIcons();
  assert.equal(nodes[0].getAttribute('data-lucide'), 'play');
  assert.equal(nodes[0].getAttribute('width'), '32');
  if (theme === 'glass') {
    assert.equal(nodes[0].getAttribute('viewBox'), '0 0 24 24');
    assert.equal(nodes[0].getAttribute('stroke'), 'currentColor');
    assert.equal(nodes[0].getAttribute('fill'), 'none');
    assert.ok(!nodes[0].getAttribute('class').includes('aoede-glyph'));
  }
}
const retained = nodes[0];
for (let repeat = 0; repeat < 100; repeat++) library.createIcons();
assert.equal(nodes[0], retained);
nodes.push(new Element('i', { 'data-lucide': 'menu', 'aria-label': 'Navigation' }));
library.createIcons();
assert.equal(nodes.at(-1).getAttribute('aria-label'), 'Navigation');
assert.equal(nodes.at(-1).hasAttribute('aria-hidden'), false);
nodes.push(new Element('i', { 'data-lucide': 'heart', fill: 'var(--accent)' }));
document.documentElement.dataset.theme = 'classic';
library.createIcons();
assert.ok(nodes.at(-1).children.length); // Filled recovered heart rather than the outline glyph.
document.documentElement.dataset.theme = 'glass';
library.icons.Heart = ['svg', defaults, []];
library.createIcons();
assert.equal(nodes.at(-1).getAttribute('fill'), 'var(--accent)'); // A theme change keeps a saved track visibly saved.
nodes.push(new Element('i', { 'data-lucide': 'unknown' }));
library.createIcons();
assert.equal(nodes.at(-1).localName, 'i');
console.log('PASS: scoped, idempotent UI icon rendering, theme coordinate changes, attributes and accessibility.');
