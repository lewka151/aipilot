@echo off
setlocal
cd /d "%~dp0"
chcp 65001 >nul 2>&1

if exist "%~dp0.tmp-jdk21\jdk-21.0.12+8\bin\java.exe" (
  set "JAVA_HOME=%~dp0.tmp-jdk21\jdk-21.0.12+8"
  set "PATH=%JAVA_HOME%\bin;%PATH%"
  echo Using JAVA_HOME=%JAVA_HOME%
) else (
  echo Gradle toolchain JDK 21 will be used. Do not start the UI with java -jar.
)

set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
set "GRADLE_OPTS=-Dfile.encoding=UTF-8"

if "%~1"=="--headless" (
  call gradlew.bat run --args="--headless"
) else (
  call gradlew.bat run
)
