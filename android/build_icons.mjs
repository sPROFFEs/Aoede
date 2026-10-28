// Native controls use the exact vector geometry already shipped by the web themes.
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const project = path.dirname(fileURLToPath(import.meta.url));
const root = path.dirname(project);
const frontend = fs.existsSync(path.join(root, 'Aoede/assets'))
  ? path.join(root, 'Aoede')
  : path.join(project, 'frontend-source');
const require = createRequire(import.meta.url);
const lucide = require(path.join(frontend, 'assets/vendor/lucide.min.js'));
const source = fs.readFileSync(path.join(frontend, 'assets/js/ui_icons.js'), 'utf8');
const context = { window: {} };
vm.runInNewContext(source.replace('const recovered = {', 'const recovered = globalThis.geometry = {'), context);
const names = new Set([
  ...Object.keys(context.geometry.classic),
  ...Object.keys(context.geometry.vitrine),
  'compass',
  'ellipsis',
  'ellipsis-vertical',
  'user',
  'users',
  'settings',
  'info',
  'volume-2',
  'volume-x',
  'mic-2',
  'timer',
  'list-plus',
  'download-cloud',
  'cloud-off',
  'radio',
  'globe',
  'music-2',
  'arrow-up-down',
  'sliders-horizontal',
  'refresh-cw',
  'log-out',
  'lock',
  'key-round',
  'image',
  'palette',
  'sparkles',
  'shield',
  'clock',
  'headphones',
  'sun',
  'moon',
  'circle-arrow-down',
  'arrow-down-to-line',
  'fast-forward',
  'rewind',
  'grid-2x2',
  'eye',
  'eye-off',
  'smartphone',
  'cookie',
  'messages-square',
  'audio-lines',
  'copy',
  'activity',
  'archive',
  'arrow-left',
  'pencil',
  'rotate-ccw',
  'hard-drive',
  'chart-column',
  'list-checks',
  'cpu',
  'layers',
  'save',
  'grip-vertical',
  'arrow-up',
  'arrow-down',
  'flag',
  'list-plus',
  'radio-tower',
  'download-cloud'
]);
const result = {};
for (const theme of ['classic', 'glass', 'vitrine']) {
  result[theme] = {};
  for (const name of names) {
    const recovered = context.geometry[theme]?.[name];
    const key = name.replace(/(^|[-_\s]+)(\w)/g, (_, _prefix, char) => char.toUpperCase());
    const definition = lucide[key];
    if (recovered) result[theme][name] = { viewBox: recovered[0], fill: true, nodes: recovered[1] };
    else if (definition) result[theme][name] = { viewBox: '0 0 24 24', fill: false, nodes: definition[2] };
  }
}
const output = JSON.stringify(result) + '\n';
const destination = path.join(project, 'app/src/main/assets/native/icons.json');
if (process.argv.includes('--check')) {
  if (fs.readFileSync(destination, 'utf8') !== output)
    throw new Error('Run node android/build_icons.mjs after changing web icons');
  console.log('Native icon geometry matches all three web themes.');
} else {
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  fs.writeFileSync(destination, output);
}
