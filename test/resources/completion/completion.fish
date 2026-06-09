function _babashka_cli_complete_myprogram
    set -l toks (commandline --tokenize --cut-at-cursor)
    set -l prog $toks[1]
    set -e toks[1]
    set -l cur (commandline --current-token)
    for line in ($prog org.babashka.cli/completions complete --shell fish -- $toks "$cur" 2>/dev/null)
        if test "$line" = org.babashka.cli/file-completion
            __fish_complete_path "$cur"
        else
            echo $line
        end
    end
end
complete --command myprogram --no-files --arguments "(_babashka_cli_complete_myprogram)"
