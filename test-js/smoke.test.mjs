// Smoke test of the published JS API (@babashka/cli) via the curated entry.
// Uses node:test and squint's value-equality `=` (node's deepStrictEqual trips
// on the metadata Symbol parse-opts attaches; deepEqual is too loose for coerce).
// Run: npm test  (compiles src to js/, then `node --test`).
import { test } from 'node:test';
import assert from 'node:assert';
import { _EQ_ as eq } from 'squint-cljs/core.js';
import {
  parseOpts, parseArgs, parseCmds, dispatch, coerce, coerceOpts,
  formatOpts, mergeOpts,
} from '../index.mjs';

const is = (actual, expected) =>
  assert.ok(eq(actual, expected),
            `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);

test('parseOpts', () => {
  is(parseOpts(['--foo', '1', '--bar']), { foo: 1, bar: true });
  is(parseOpts(['--x', '1', '--y', '1.5'], { coerce: { x: 'int', y: 'double' } }), { x: 1, y: 1.5 });
  is(parseOpts(['-f', 'bar'], { alias: { f: 'foo' } }), { foo: 'bar' });
});

test('parseArgs', () => {
  const pa = parseArgs(['--foo', '1', 'x', 'y']);
  is(pa.opts, { foo: 1 });
  is(pa.args, ['x', 'y']);
});

test('parseCmds', () => {
  is(parseCmds(['sub', 'cmd', '--opt']).cmds, ['sub', 'cmd']);
});

test('coerce', () => {
  is(coerce('42', 'int'), 42);
  is(coerceOpts({ n: '3' }, { coerce: { n: 'int' } }), { n: 3 });
});

test('mergeOpts', () => {
  is(mergeOpts({ a: 1 }, { b: 2 }), { a: 1, b: 2 });
});

test('dispatch', () => {
  const tree = {
    fn: () => 'root',
    cmd: { add: { fn: (m) => ['add', m.opts] } },
  };
  is(dispatch(tree, ['add', '--n', '3'], { coerce: { n: 'int' } }), ['add', { n: 3 }]);
  is(dispatch(tree, []), 'root');
});

test('formatOpts', () => {
  const help = formatOpts({ spec: { foo: { desc: 'the foo', alias: 'f' } } });
  assert.ok(help.includes('--foo'), help);
});
