_babashka_cli_complete_myprogram()
{
    local cur words cword
    if declare -F _init_completion >/dev/null 2>&1; then
        _init_completion -n = || return
    else
        words=("${COMP_WORDS[@]}"); cword=$COMP_CWORD; cur="${COMP_WORDS[COMP_CWORD]}"
    fi
    local out
    out=$("${words[0]}" org.babashka.cli/completions complete --shell bash -- "${words[@]:1:cword}" 2>/dev/null)
    local IFS=$'\n'
    case "$out" in
        *org.babashka.cli/file-completion*)
            compopt -o filenames 2>/dev/null
            COMPREPLY+=( $(compgen -f -- "$cur") ) ;;
    esac
    local values
    values=$(grep -v '^org.babashka.cli/file-completion$' <<< "$out" | cut -f1)
    COMPREPLY+=( $(compgen -W "$values" -- "$cur") )
}
complete -F _babashka_cli_complete_myprogram myprogram
