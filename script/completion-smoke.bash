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

bbtest --org.babashka.cli/completion-snippet bash > "$tmp/comp.bash"
source "$tmp/comp.bash"

fail=0
assert() { # <command line> <expected candidate>...
  local line="$1"; shift
  COMP_LINE="$line"; COMP_POINT=${#line}
  IFS=' ' read -ra COMP_WORDS <<< "$line"
  [[ "$line" == *" " ]] && COMP_WORDS+=("")
  COMP_CWORD=$(( ${#COMP_WORDS[@]} - 1 )); COMPREPLY=()
  _babashka_cli_dynamic_completion
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

[[ $fail == 0 ]] && echo "bash: PASS" || echo "bash: FAIL"
exit $fail
