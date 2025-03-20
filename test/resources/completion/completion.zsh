#compdef myprogram
source <( "${words[1]}" --org.babashka.cli/complete "zsh" "${words[*]// / }" )
