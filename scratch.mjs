import { deepEqual } from 'assert';
import { parse_args } from './js/babashka/cli.mjs';

console.log(parse_args(['-a', '1', '-a', '1'], {coerce: {a: ['long']}}))

console.log(parse_args(["--no-option", "--no-this-exists"], ({ "coerce": ({ "no-this-exists": "bool" }) })));
