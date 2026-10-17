// Run: node --experimental-vm-modules scripts/test_glass_optics.mjs
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
const module = new vm.SourceTextModule((await readFile(new URL('../Aoede/assets/js/modules/glass_optics.js', import.meta.url), 'utf8')) + '\nexport { lensPixel };');
await module.link(() => { throw new Error('The lens has no runtime dependencies'); });
await module.evaluate();
const pixel = (...args) => Array.from(module.namespace.lensPixel(...args));
assert.deepEqual(pixel(100, 50, 200, 100, 24, 12), [128, 128, 0]);
assert.deepEqual(pixel(-1, 50, 200, 100, 24, 12), [128, 128, 0]);
const left = pixel(4, 50, 200, 100, 24, 12), right = pixel(196, 50, 200, 100, 24, 12);
assert.ok(left[0] < 128 && right[0] > 128 && left[2] > 0);
assert.ok(Math.abs(left[0] + right[0] - 256) < 1e-9);
assert.equal(left[1], 128); assert.equal(left[2], right[2]);
const top = pixel(100, 4, 200, 100, 24, 12);
assert.equal(top[0], 128); assert.ok(top[1] < 128);
for (let y = 0; y < 100; y++) for (let x = 0; x < 200; x++)
  for (const channel of pixel(x, y, 200, 100, 24, 12)) assert.ok(Number.isFinite(channel) && channel >= 0 && channel <= 255);
console.log('PASS: lens leaves the centre neutral, bends opposite rims symmetrically and keeps channels finite.');
