function _babashka_cli_dynamic_completion
    set --local COMP_LINE (commandline --cut-at-cursor)
    myprogram --org.babashka.cli/complete fish $COMP_LINE
end
complete --command myprogram --no-files --arguments "(_babashka_cli_dynamic_completion)"
