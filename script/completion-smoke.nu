#!/usr/bin/env nu
# Integration smoke test: install the generated nushell completion for the
# fixture CLI and call the registered external completer directly. Nushell has
# no headless TAB driver (like bash COMPREPLY or fish `complete -C`), but the
# external completer closure IS the whole stub, so calling it with the spans
# nushell would pass covers the same ground.
#
# `source` needs a parse-time constant path, so the checks run in a child `nu`
# on a generated script that embeds the tmp path as a literal.
let repo = ($env.FILE_PWD | path join ".." | path expand)
let tmp = (mktemp -d)

# a `bbtest` on PATH that runs the fixture with the local babashka.cli source
$"#!/usr/bin/env bash\nexec bb --classpath \"($repo)/src\" \"($repo)/test-resources/completion/fixture.clj\" \"$@\"\n"
| save -f ($tmp | path join "bbtest")
^chmod +x ($tmp | path join "bbtest")
$env.PATH = ($env.PATH | prepend $tmp)

^bbtest org.babashka.cli/completions snippet --shell nushell | save -f ($tmp | path join "comp.nu")

let checks = ([
  $"source '($tmp)/comp.nu'"
  "let comp = $env.config.completions.external.completer"
  "def check [comp: closure, spans: list<string>, expected: list<string>] {"
  "    let out = (do $comp $spans | default [] | each {|r| $r.value})"
  "    let missing = ($expected | where {|e| $e not-in $out})"
  "    if ($missing | is-empty) {"
  "        print $'ok   [($spans | str join \" \")] -> ($out | str join \" \")'"
  "        false"
  "    } else {"
  "        print $'FAIL [($spans | str join \" \")]: missing ($missing) in ($out)'"
  "        true"
  "    }"
  "}"
  "mut failed = ["
  "  (check $comp ['bbtest' ''] ['deploy' 'status'])"
  "  (check $comp ['bbtest' 'de'] ['deploy'])"
  "  (check $comp ['bbtest' 'deploy' '--'] ['--env' '--force'])"
  "  (check $comp ['bbtest' 'deploy' '--env' ''] ['dev' 'staging' 'prod'])"
  "  (check $comp ['bbtest' 'deploy' '--env' 'st'] ['staging'])"
  "  (check $comp ['bbtest' 'deploy' '--env=st'] ['--env=staging'])"
  "]"
  "# positional file arg (cat <file>) -> null = defer to nushell's file completion"
  "if (do $comp ['bbtest' 'cat' 'zz']) == null {"
  "    print 'ok   [bbtest cat zz] -> file completion'"
  "} else {"
  "    print 'FAIL [bbtest cat zz]: expected null (file completion fallback)'"
  "    $failed = ($failed | append true)"
  "}"
  "if ($failed | any {|f| $f}) { print 'nushell: FAIL'; exit 1 }"
  "print 'nushell: PASS'"
] | str join "\n")
$checks | save -f ($tmp | path join "checks.nu")

let res = (^nu ($tmp | path join "checks.nu") | complete)
print -n $res.stdout
print -n -e $res.stderr
rm -rf $tmp
exit $res.exit_code
