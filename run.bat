@echo off
rem Precipitation launcher: build if needed, then open the game (main menu).
rem The viewer starts the Python simulation itself, one process per game, and stops it again
rem when you leave the game or close the window.
rem Extra arguments are passed through, e.g.  run.bat --fake   or   run.bat --new
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Install a JDK ^(25 or newer^) and make sure it is on PATH.
  pause
  exit /b 1
)
rem Rebuild when there is no finished build, or when any source file (new ones included) is newer than it.
set NEED=0
if not exist viewer\out\precip\Main.class set NEED=1
if not exist viewer\out\build.stamp set NEED=1
if "%NEED%"=="0" (
  for /f %%F in ('powershell -NoProfile -Command "$o=(Get-Item viewer\out\build.stamp).LastWriteTime; if ((Get-ChildItem viewer\src\precip\*.java | Where-Object { $_.LastWriteTime -gt $o }).Count -gt 0) { 1 } else { 0 }"') do set NEED=%%F
)
if "%NEED%"=="1" (
  call "%~dp0build.bat"
  if errorlevel 1 (
    echo.
    echo The viewer could not be built - see the messages above.
    pause
    exit /b 1
  )
)
java -Dsun.java2d.uiScale.enabled=true -cp viewer\out precip.Main %*
set RC=%ERRORLEVEL%
endlocal & exit /b %RC%
