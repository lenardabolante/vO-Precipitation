@echo off
rem Build the Java viewer (plain javac, no dependencies).
rem Compiles every source file in viewer\src\precip and, on success, writes viewer\out\build.stamp
rem (run.bat compares the sources against that stamp to decide whether a rebuild is needed).
setlocal
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 (
  echo Build FAILED: javac was not found. Install a JDK ^(25 or newer^) and make sure it is on PATH.
  exit /b 1
)
if not exist viewer\out mkdir viewer\out
if exist viewer\out\build.stamp del /q viewer\out\build.stamp
javac -encoding UTF-8 -d viewer\out viewer\src\precip\*.java
if errorlevel 1 (
  echo Build FAILED.
  exit /b 1
)
echo built %DATE% %TIME%> viewer\out\build.stamp
echo Build OK: viewer\out
endlocal
