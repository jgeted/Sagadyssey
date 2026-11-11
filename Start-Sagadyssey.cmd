@echo off
setlocal
title Sagadyssey Test Client

pushd "%~dp0"
if errorlevel 1 goto workspace_error

rem Prefer an installed Java 21 without changing the user's system settings.
for /d %%J in ("%ProgramFiles%\Eclipse Adoptium\jdk-21*") do (
    if exist "%%~fJ\bin\java.exe" set "JAVA_HOME=%%~fJ"
)

if not exist "%~dp0gradlew.bat" goto wrapper_error

if /i "%~1"=="--check" goto check

echo Starting Minecraft 1.21.11 with Sagadyssey...
echo Please wait while Gradle prepares the game.
echo Keep this window open until you exit Minecraft.
echo.
call "%~dp0gradlew.bat" runClient --console=plain
set "SAGA_EXIT=%ERRORLEVEL%"
popd
if not "%SAGA_EXIT%"=="0" (
    echo.
    echo Startup failed. Please send the error shown above.
    pause
)
endlocal & exit /b %SAGA_EXIT%

:check
if defined JAVA_HOME (
    if not exist "%JAVA_HOME%\bin\java.exe" goto java_error
    "%JAVA_HOME%\bin\java.exe" -version
) else (
    java -version
)
if errorlevel 1 goto java_error
echo Launcher check OK. Project: %CD%
popd
endlocal & exit /b 0

:java_error
echo Java was not found. Install Java 21 or set JAVA_HOME to its folder.
goto fail

:wrapper_error
echo gradlew.bat was not found beside this launcher.
goto fail

:workspace_error
echo The project folder could not be opened.
if /i "%~1"=="--check" exit /b 1
pause
exit /b 1

:fail
popd
if /i "%~1"=="--check" exit /b 1
pause
exit /b 1
