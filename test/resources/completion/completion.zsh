#compdef myprogram
_babashka_cli_complete_myprogram() {
    local -a completions described
    completions=("${(@f)$("${words[1]}" org.babashka.cli/completions complete --shell zsh -- "${(@)words[2,CURRENT]}" 2>/dev/null)}")
    local c
    for c in $completions; do described+=("${c//$'\t'/:}"); done
    _describe -t commands myprogram described
}
# register for the bare name and for path invocations (./prog, /abs/prog)
compdef _babashka_cli_complete_myprogram '*/myprogram' myprogram
