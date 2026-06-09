_babashka_cli_dynamic_completion()
{
    local line="${COMP_LINE:0:$COMP_POINT}"
    local IFS=$'\n'
    local values
    values=$("${COMP_WORDS[0]}" org.babashka.cli/completions complete --shell bash --line "$line" | cut -f1)
    COMPREPLY=( $(compgen -W "$values" -- "${COMP_WORDS[COMP_CWORD]}") )
}
complete -F _babashka_cli_dynamic_completion myprogram
