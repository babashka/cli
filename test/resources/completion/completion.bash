_babashka_cli_dynamic_completion()
{
    source <( "$1" --babashka.cli/complete "bash" "${COMP_WORDS[*]// / }" )
}
complete -o nosort -F _babashka_cli_dynamic_completion myprogram
