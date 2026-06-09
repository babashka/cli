#compdef myprogram
_babashka_cli_complete_myprogram() {
    local -a completions described
    completions=("${(@f)$("${words[1]}" org.babashka.cli/completions complete --shell zsh -- "${(@)words[2,CURRENT]}" 2>/dev/null)}")
    local do_files=
    if (( ${completions[(I)org.babashka.cli/file-completion]} )); then do_files=1; fi
    completions=(${completions:#org.babashka.cli/file-completion})
    local c
    for c in $completions; do described+=("${c//$'\t'/:}"); done
    _describe -t commands myprogram described
    [[ -n $do_files ]] && _files
}
# register for the bare name and for path invocations (./prog, /abs/prog)
compdef _babashka_cli_complete_myprogram '*/myprogram' myprogram
