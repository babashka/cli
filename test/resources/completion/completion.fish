function _babashka_cli_dynamic_completion
    myprogram --org.babashka.cli/complete fish (commandline --cut-at-cursor)
end
complete --command myprogram --no-files --arguments "(_babashka_cli_dynamic_completion)"
