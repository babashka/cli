function _babashka_cli_complete_myprogram
    set -l toks (commandline --tokenize --cut-at-cursor)
    set -e toks[1]
    set -l cur (commandline --current-token)
    myprogram org.babashka.cli/completions complete --shell fish -- $toks "$cur"
end
complete --command myprogram --no-files --arguments "(_babashka_cli_complete_myprogram)"
