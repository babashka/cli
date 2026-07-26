#compdef myprogram
_babashka_cli_complete_myprogram() {
    local -a lines described optdescribed bareopt bare
    lines=("${(@f)$("${words[1]}" org.babashka.cli/completions complete --shell zsh -- "${(@)words[2,CURRENT]}" 2>/dev/null)}")
    local do_files= l v d
    for l in $lines; do
        if [[ $l == org.babashka.cli/file-completion ]]; then do_files=1; continue; fi
        v="${l%%$'\t'*}"; d=
        [[ $l == *$'\t'* ]] && d="${l#*$'\t'}"
        # _describe eats backslashes and splits on ':', so escape both
        v="${v//\\/\\\\}"; d="${d//\\/\\\\}"
        v="${v//:/\\:}"; d="${d//:/\\:}"
        # an option is an option with or without a description: classifying an
        # undescribed one as a value would offer it where zsh hides the rest
        if [[ $v == -* ]]; then
            if [[ -z $d ]]; then bareopt+=("$v"); else optdescribed+=("$v:$d"); fi
        elif [[ -z $d ]]; then bare+=("$v")
        else described+=("$v:$d"); fi
    done
    local ret=1
    # claim success whenever we produced candidates: _describe's own exit status
    # is not reliably 0 under a user matcher-list / multi-completer setup, and a
    # non-zero return makes zsh retry other completers (_match, _approximate, ...)
    # and re-list everything with detached descriptions
    (( $#described )) && { _describe -t completions completion described; ret=0; }
    # options arrive alone (the emission gates them behind a dash-prefixed or
    # flags-only word), so their merged-alias display lines cannot inflate the
    # column layout of another group
    (( $#optdescribed )) && { _describe -t options option optdescribed; ret=0; }
    (( $#bareopt )) && { _describe -t options option bareopt; ret=0; }
    (( $#bare )) && { _describe -t values value bare; ret=0; }
    [[ -n $do_files ]] && { _files; ret=0; }
    return $ret
}
# zsh hides options until a dash is typed. After a command there is usually
# nothing else to complete, so opt out for these programs only
zstyle ':completion:*:*:myprogram:*:options' prefix-needed false
# register the bare name(s); zsh's _normal completes ./name and /abs/name via the basename
compdef _babashka_cli_complete_myprogram myprogram
