_babashka_cli_complete_myprogram()
{
    local cur words cword
    # -n =: keeps --opt=val and ns:val as single words (bash splits on = and :
    # via COMP_WORDBREAKS); the no-bash-completion fallback below cannot, so a : in
    # a value is only fully handled when bash-completion is installed
    if declare -F _init_completion >/dev/null 2>&1; then
        _init_completion -n =: || return
    else
        words=("${COMP_WORDS[@]}"); cword=$COMP_CWORD; cur="${COMP_WORDS[COMP_CWORD]}"
    fi
    # keep our candidate order. Only on the real compopt builtin (bash 4.4+): on
    # bash 3.2 with bash-completion, compopt is a shim that forwards to `complete`,
    # which rejects -o nosort. The probe is cached: its answer never changes
    if [[ -z ${_babashka_cli_compopt_t+x} ]]; then _babashka_cli_compopt_t=$(type -t compopt); fi
    [[ $_babashka_cli_compopt_t == builtin ]] && compopt -o nosort 2>/dev/null
    local out
    out=$("${words[0]}" org.babashka.cli/completions complete --shell bash -- "${words[@]:1:cword}" 2>/dev/null)
    # candidates come back already prefix-filtered; insert them verbatim.
    # read -r keeps them out of word splitting and pathname expansion (an
    # unquoted loop would glob a '*.txt' candidate against the cwd), printf %q
    # escapes spaces/quotes for insertion
    local line v
    while IFS= read -r line; do
        [[ -n $line ]] || continue
        if [[ $line == org.babashka.cli/file-completion ]]; then
            compopt -o filenames 2>/dev/null
            while IFS= read -r v; do
                [[ -n $v ]] && COMPREPLY+=( "$v" )
            done < <(compgen -f -- "$cur")
        else
            v=${line%%$'\t'*}
            printf -v v '%q' "$v"
            COMPREPLY+=( "$v" )
        fi
    done <<< "$out"
    # bash re-inserts from the last COMP_WORDBREAKS char (e.g. : or =); strip that
    # prefix from each candidate so colon/equals values complete without duplication
    local wb pre i
    for wb in : = ; do
        if [[ "$cur" == *"$wb"* && "$COMP_WORDBREAKS" == *"$wb"* ]]; then
            pre="${cur%"${cur##*$wb}"}"
            for ((i=0; i<${#COMPREPLY[@]}; i++)); do COMPREPLY[$i]="${COMPREPLY[$i]#"$pre"}"; done
        fi
    done
}
complete -F _babashka_cli_complete_myprogram myprogram
