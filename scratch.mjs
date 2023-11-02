import { deepEqual } from 'assert';

var x = {
  type: 'org.babashka/cli',
  cause: 'coerce',
  msg: 'Coerce failure: cannot transform input "dude" to long',
  option: 'b',
  value: 'dude',
  spec: null
}

var y = {
  spec: undefined,
  type: 'org.babashka/cli',
  cause: 'coerce',
  msg: 'Coerce failure: cannot transform input "dude" to long',
  option: 'b',
  value: 'dude'
}

deepEqual(x,y);
