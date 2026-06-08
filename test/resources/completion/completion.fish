function _babashka_cli_dynamic_completion
    set -lx COMP_LINE (commandline --cut-at-cursor)
    set -lx BABASHKA_CLI_COMPLETE fish
    myprogram
end
complete --command myprogram --no-files --arguments "(_babashka_cli_dynamic_completion)"
