_babashka_cli_complete_myprogram()
{
    local cur words cword
    if declare -F _init_completion >/dev/null 2>&1; then
        _init_completion -n = || return
    else
        words=("${COMP_WORDS[@]}"); cword=$COMP_CWORD; cur="${COMP_WORDS[COMP_CWORD]}"
    fi
    local values
    values=$("${words[0]}" org.babashka.cli/completions complete --shell bash -- "${words[@]:1:cword}" | cut -f1)
    local IFS=$'\n'
    COMPREPLY=( $(compgen -W "$values" -- "$cur") )
}
complete -F _babashka_cli_complete_myprogram myprogram
