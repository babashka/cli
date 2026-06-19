#compdef myprogram
_babashka_cli_complete_myprogram() {
    local -a lines described
    lines=("${(@f)$("${words[1]}" org.babashka.cli/completions complete --shell zsh -- "${(@)words[2,CURRENT]}" 2>/dev/null)}")
    local do_files= l v d
    for l in $lines; do
        if [[ $l == org.babashka.cli/file-completion ]]; then do_files=1; continue; fi
        v="${l%%$'\t'*}"; d=
        [[ $l == *$'\t'* ]] && d="${l#*$'\t'}"
        # _describe eats backslashes and splits on ':', so escape both
        v="${v//\\/\\\\}"; d="${d//\\/\\\\}"
        v="${v//:/\\:}"; d="${d//:/\\:}"
        described+=("$v${d:+:$d}")
    done
    local ret=1
    # claim success whenever we produced candidates: _describe's own exit status
    # is not reliably 0 under a user matcher-list / multi-completer setup, and a
    # non-zero return makes zsh retry other completers (_match, _approximate, ...)
    # and re-list everything with detached descriptions
    (( $#described )) && { _describe -t values completion described; ret=0; }
    [[ -n $do_files ]] && { _files; ret=0; }
    return $ret
}
# register the bare name(s); zsh's _normal completes ./name and /abs/name via the basename
compdef _babashka_cli_complete_myprogram myprogram
