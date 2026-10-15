$ErrorActionPreference = 'Stop'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java.exe' }
try {
    $ErrorActionPreference = 'Continue'
    $version = (& $java -version 2>&1 | Out-String)
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "(\d+)' -or [int]$Matches[1] -lt 21) {
        [Console]::Error.WriteLine('juno requires JDK 21+; set JAVA_HOME.')
        exit 2
    }
    # CRT quoting also works on Windows PowerShell 5.1 (.NET Framework has no ArgumentList).
    function Quote-Native([string]$Value) {
        return '"' + (($Value -replace '(\\*)"', '$1$1\"') -replace '(\\+)$', '$1$1') + '"'
    }
    $nativeArgs = @('-jar', (Join-Path $PSScriptRoot '../lib/juno.jar')) + @($args)
    $start = New-Object System.Diagnostics.ProcessStartInfo
    $start.FileName = $java
    $start.UseShellExecute = $false
    $start.Arguments = (($nativeArgs | ForEach-Object { Quote-Native ([string]$_) }) -join ' ')
    $process = [System.Diagnostics.Process]::Start($start)
    $process.WaitForExit()
    exit $process.ExitCode
} catch {
    [Console]::Error.WriteLine('juno requires JDK 21+; set JAVA_HOME. ' + $_.Exception.Message)
    exit 2
}
