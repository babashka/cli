#compdef myprogram
source <( "${words[1]}" --babashka.cli/complete "zsh" "${words[*]// / }" )
