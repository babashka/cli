_babashka_cli_dynamic_completion()
{
    local IFS=$'\n'
    local values
    values=$(COMP_LINE="${COMP_LINE:0:$COMP_POINT}" BABASHKA_CLI_COMPLETE=bash "${COMP_WORDS[0]}" | cut -f1)
    COMPREPLY=( $(compgen -W "$values" -- "${COMP_WORDS[COMP_CWORD]}") )
}
complete -F _babashka_cli_dynamic_completion myprogram
