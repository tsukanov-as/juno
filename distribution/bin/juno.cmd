@echo off
setlocal
set "JUNO_JAVA=java.exe"
if defined JAVA_HOME set "JUNO_JAVA=%JAVA_HOME%\bin\java.exe"
powershell.exe -NoProfile -Command "$ErrorActionPreference='Continue'; try { $v=(& $env:JUNO_JAVA -version 2>&1 | Out-String); if ($LASTEXITCODE -ne 0 -or $v -notmatch 'version .(\d+)' -or [int]$Matches[1] -lt 21) { exit 2 }; exit 0 } catch { exit 2 }"
if errorlevel 1 (
  echo juno requires JDK 21+; set JAVA_HOME. 1>&2
  exit /b 2
)
for /f "tokens=2 delims=:" %%C in ('chcp') do set "JUNO_PREVIOUS_CP=%%C"
chcp 65001 >nul
"%JUNO_JAVA%" -jar "%~dp0..\lib\juno.jar" %*
set "JUNO_EXIT_CODE=%ERRORLEVEL%"
if defined JUNO_PREVIOUS_CP chcp %JUNO_PREVIOUS_CP% >nul
exit /b %JUNO_EXIT_CODE%
