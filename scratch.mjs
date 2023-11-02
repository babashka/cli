import { deepEqual } from 'assert';
import { parse_args } from './js/babashka/cli.mjs';

console.log(parse_args(['-a', '1', '-a', '1'], {coerce: {a: ['long']}}))
