#!/usr/bin/env pwsh
# Integration smoke test: install the generated powershell completion for the
# fixture CLI and drive it headless via TabExpansion2, asserting candidates.
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot/..").Path
$tmp = New-Item -ItemType Directory -Path (Join-Path ([IO.Path]::GetTempPath()) ([Guid]::NewGuid()))
try {
  $wrapper = Join-Path $tmp 'bbtest'
  $body = "#!/usr/bin/env bash`nexec bb --classpath `"$repo/src`" `"$repo/test-resources/completion/fixture.clj`" `"`$@`"`n"
  [IO.File]::WriteAllText($wrapper, $body)
  & chmod +x $wrapper
  $env:PATH = "$tmp" + [IO.Path]::PathSeparator + $env:PATH

  & bbtest --org.babashka.cli/completion-snippet powershell | Out-String | Invoke-Expression

  $script:fail = 0
  function Check($line, [string[]]$expected) {
    $r = TabExpansion2 -inputScript $line -cursorColumn $line.Length
    $out = @($r.CompletionMatches.CompletionText)
    $ok = $true
    foreach ($e in $expected) {
      if ($out -notcontains $e) {
        Write-Output "FAIL [$line]: missing '$e' in '$($out -join ' ')'"; $ok = $false; $script:fail = 1
      }
    }
    if ($ok) { Write-Output "ok   [$line] -> $($out -join ' ')" }
  }

  Check 'bbtest '                @('deploy','status')
  Check 'bbtest de'              @('deploy')
  Check 'bbtest deploy --'       @('--env','--force')
  Check 'bbtest deploy --env '   @('dev','staging','prod')
  Check 'bbtest deploy --env st' @('staging')

  if ($script:fail -eq 0) { Write-Output 'powershell: PASS' } else { Write-Output 'powershell: FAIL' }
  exit $script:fail
} finally {
  Remove-Item -Recurse -Force $tmp
}
