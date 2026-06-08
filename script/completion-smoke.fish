#!/usr/bin/env fish
# Integration smoke test: install the generated fish completion for the fixture
# CLI and drive it headless via `complete -C`, asserting candidates.
set -l repo (cd (dirname (status filename))/..; and pwd)
set -g tmp (mktemp -d)
function _cleanup --on-event fish_exit; rm -rf $tmp; end

printf '#!/usr/bin/env bash\nexec bb --classpath "%s/src" "%s/test-resources/completion/fixture.clj" "$@"\n' $repo $repo > $tmp/bbtest
chmod +x $tmp/bbtest
set -gx PATH $tmp $PATH

bbtest --org.babashka.cli/completion-snippet fish > $tmp/comp.fish
source $tmp/comp.fish

set -g fail 0
function check # <command line> <expected candidate>...
    set -l line $argv[1]
    set -l out (complete -C "$line" | cut -f1)
    set -l ok 1
    for e in $argv[2..-1]
        if not contains -- $e $out
            echo "FAIL [$line]: missing '$e' in '$out'"
            set ok 0
            set -g fail 1
        end
    end
    test $ok -eq 1; and echo "ok   [$line] -> $out"
end

check "bbtest " deploy status
check "bbtest de" deploy
check "bbtest deploy --" --env --force
check "bbtest deploy --env " dev staging prod
check "bbtest deploy --env st" staging

test $fail -eq 0; and echo "fish: PASS"; or echo "fish: FAIL"
exit $fail
