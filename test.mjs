import { parse_opts } from './js/babashka/cli.mjs';

function demo(args, opts) {
  console.log(args, opts, "=>",  parse_opts(args, opts));
}

demo(['--foo', '1'], {})
demo(['--foo', '1'], {coerce: {foo: "string"}})
demo(['--foo', '1'], {coerce: {foo: ["string"]}})
