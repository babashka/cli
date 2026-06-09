#!/usr/bin/env bash
# Integration smoke test: install the generated bash completion for the fixture
# CLI and drive it headless, asserting candidates. Run from the repo root (or
# anywhere - it locates the repo from its own path).
set -uo pipefail

repo="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# a `bbtest` on PATH that runs the fixture with the local babashka.cli source
cat > "$tmp/bbtest" <<EOF
#!/usr/bin/env bash
exec bb --classpath "$repo/src" "$repo/test-resources/completion/fixture.clj" "\$@"
EOF
chmod +x "$tmp/bbtest"
export PATH="$tmp:$PATH"

bbtest org.babashka.cli/completions snippet --shell bash > "$tmp/comp.bash"
source "$tmp/comp.bash"

fail=0
# readline's default wordbreaks; unset in non-interactive bash, but the stub's
# prefix-strip loop reads it when the current word contains = or :
COMP_WORDBREAKS=${COMP_WORDBREAKS-$' \t\n"\'><=;|&(:'}
assert() { # <command line> <expected candidate>...
  local line="$1"; shift
  COMP_LINE="$line"; COMP_POINT=${#line}
  IFS=' ' read -ra COMP_WORDS <<< "$line"
  [[ "$line" == *" " ]] && COMP_WORDS+=("")
  COMP_CWORD=$(( ${#COMP_WORDS[@]} - 1 )); COMPREPLY=()
  _babashka_cli_complete_bbtest
  local out=" ${COMPREPLY[*]} " ok=1
  for exp in "$@"; do
    if [[ "$out" != *" $exp "* ]]; then
      echo "FAIL [$line]: missing '$exp' in '${out# }'"; ok=0; fail=1
    fi
  done
  [[ $ok == 1 ]] && echo "ok   [$line] -> ${out# }"
}

assert "bbtest "                 deploy status
assert "bbtest de"               deploy
assert "bbtest deploy --"        --env --force
assert "bbtest deploy --env "    dev staging prod
assert "bbtest deploy --env st"  staging
# --opt=val: the callback emits the full token, the stub strips the wordbreak
# prefix (--env=) so bash re-inserts just the value
assert "bbtest deploy --env=st"  staging

# positional file arg (cat <file>) -> shell file completion
mkdir "$tmp/fc"; : > "$tmp/fc/zzsmoke.txt"
pushd "$tmp/fc" >/dev/null
assert "bbtest cat zz"           zzsmoke.txt
popd >/dev/null

[[ $fail == 0 ]] && echo "bash: PASS" || echo "bash: FAIL"
exit $fail
