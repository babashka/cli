#compdef myprogram
_babashka_cli_dynamic_completion() {
    local line="${(j: :)words[1,CURRENT]}"
    local -a completions
    completions=("${(@f)$("${words[1]}" --org.babashka.cli/complete zsh "$line")}")
    local -a described
    local c
    for c in $completions; do described+=("${c//$'\t'/:}"); done
    _describe -t commands myprogram described
}
# register for the bare name and for path invocations (./prog, /abs/prog)
compdef _babashka_cli_dynamic_completion '*/myprogram' myprogram
