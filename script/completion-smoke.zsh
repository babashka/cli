#!/usr/bin/env zsh
# Integration smoke test for the generated zsh completion.
#
# zsh has no headless "give me the candidates" API (unlike bash COMPREPLY, fish
# `complete -C`, powershell TabExpansion2): completion only runs inside ZLE, in an
# interactive shell on a tty. So we drive a real zsh under a pseudo-tty (zpty) and
# capture candidates by overriding `compadd` (what every completion ultimately
# calls) instead of screen-scraping the rendered listing. Null-byte sentinels via
# compprefuncs/comppostfuncs bound the output, and comppostfuncs exits the shell
# so we read until EOF - no timing guesswork. Technique from zsh's Test/comptest
# and Valodim/zsh-capture-completion.
emulate -L zsh
zmodload zsh/zpty || { echo "zsh/zpty unavailable"; exit 2 }

repo=${0:A:h:h}
tmp=$(mktemp -d)
trap "rm -rf $tmp" EXIT

cat > $tmp/bbtest <<EOF
#!/usr/bin/env bash
exec bb --classpath "$repo/src" "$repo/test-resources/completion/fixture.clj" "\$@"
EOF
chmod +x $tmp/bbtest
PATH="$tmp:$PATH" command bbtest org.babashka.cli/completions snippet --shell zsh > $tmp/comp.zsh

# setup sourced into the pty zsh: completion system + compadd capture hook + our snippet
cat > $tmp/setup.zsh <<SETUP
PROMPT=
autoload -Uz compinit; compinit -u -d $tmp/.zcompdump
bindkey '^M' undefined
bindkey '^J' undefined
bindkey '^I' complete-word
mark-line () { echo -E - ZZCAPMARKZZ }
compprefuncs=( mark-line )
comppostfuncs=( mark-line exit )
zstyle ':completion:*' insert-tab false
# capture candidates: override compadd to print value (+ description) per match
compadd () {
    if [[ \${@[(I)-(O|A|D)]} -gt 0 ]]; then builtin compadd "\$@"; return \$?; fi
    typeset -a __hits __dscr
    if (( \$@[(I)-d] )); then
        local __t=\${@[\$[\${@[(i)-d]}+1]]}
        if [[ \$__t == \(* ]]; then eval "__dscr=\$__t"; else __dscr=( "\${(@P)__t}" ); fi
    fi
    builtin compadd -A __hits -D __dscr "\$@"
    [[ -n \$__hits ]] || return
    local i
    for i in {1..\$#__hits}; do
        if (( \$#__dscr >= i )); then echo -E - "\$__hits[\$i] -- \$__dscr[\$i]"
        else echo -E - "\$__hits[\$i]"; fi
    done
}
cd $tmp/fc
source $tmp/comp.zsh
echo READYOK
SETUP
mkdir -p $tmp/fc; : > $tmp/fc/zzsmoke.txt

# Sets global CAP to the captured candidate lines. NOT run in a $(...) subshell -
# zsh/zpty does not work inside command substitution.
capture () { # <command line>
  CAP=""
  zpty Z "PATH=$tmp:\$PATH zsh -f -i"
  zpty -w Z "source $tmp/setup.zsh"
  local l
  repeat 50; do zpty -r Z l; [[ $l == READYOK* ]] && break; done
  zpty -w Z "$1"$'\t'
  local tog=0
  # read until the pty closes (comppostfuncs exits the shell); candidates sit
  # between the two ZZCAPMARKZZ sentinels emitted by comppre/postfuncs
  while zpty -r Z l; do
    if [[ $l == *ZZCAPMARKZZ* ]]; then (( tog++ )) && break || continue; fi
    (( tog )) && CAP+="${l%$'\r'}"$'\n'
  done
  zpty -d Z 2>/dev/null
}

fail=0
check () { # <command line> <expected candidate>...
  local line="$1"; shift
  capture "$line"
  local ok=1
  for e in "$@"; do
    if [[ "$CAP" != *"$e"* ]]; then print "FAIL [$line]: missing '$e'"; ok=0; fail=1; fi
  done
  (( ok )) && print "ok   [$line]"
}

check "bbtest " deploy status
check "bbtest de" deploy
check "bbtest deploy --" --env --force
check "bbtest deploy --env " dev staging prod
check "bbtest deploy --env st" staging
# positional file arg (cat <file>) -> shell file completion (_files), pty cwd is $tmp/fc
check "bbtest cat zz" zzsmoke.txt

# return-code regression: the completer must return 0 when it completes, else zsh
# falls through to its other completers and re-lists (3x, detached descriptions).
# Call the function directly with the completion builtins stubbed and check $?.
rc=$(zsh -f -c "
  _describe() { return 0 }; _files() { return 0 }; compadd() { return 0 }; compdef() { return 0 }
  PATH=$tmp:\$PATH
  source $tmp/comp.zsh
  words=(bbtest); CURRENT=1
  _babashka_cli_complete_bbtest >/dev/null 2>&1
  echo \$?" 2>/dev/null)
if [[ "$rc" == 0 ]]; then print "ok   [return code 0]"; else print "FAIL [return code]: got '$rc'"; fail=1; fi

(( fail == 0 )) && print "zsh: PASS" || print "zsh: FAIL"
exit $fail
