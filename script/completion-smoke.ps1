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

  & bbtest org.babashka.cli/completions snippet --shell powershell | Out-String | Invoke-Expression

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

  # positional file arg (cat <file>) -> shell file completion (./ vs .\ varies by OS)
  New-Item -ItemType Directory (Join-Path $tmp 'fc') | Out-Null
  New-Item -ItemType File (Join-Path $tmp 'fc' 'zzsmoke.txt') | Out-Null
  Set-Location (Join-Path $tmp 'fc')
  $r = TabExpansion2 -inputScript 'bbtest cat zz' -cursorColumn ('bbtest cat zz').Length
  if (@($r.CompletionMatches.CompletionText) -match 'zzsmoke\.txt') {
    Write-Output 'ok   [bbtest cat zz] -> file completion'
  } else {
    Write-Output 'FAIL [bbtest cat zz]: no file completion'; $script:fail = 1
  }

  if ($script:fail -eq 0) { Write-Output 'powershell: PASS' } else { Write-Output 'powershell: FAIL' }
  exit $script:fail
} finally {
  Set-Location $repo
  Remove-Item -Recurse -Force $tmp
}
