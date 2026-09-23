@echo off
setlocal
cd /d "%~dp0"

echo ============================================
echo   Building and starting AntennaPod Desktop
echo ============================================
echo.

REM Use JAVA_HOME if set, otherwise fall back to java in PATH
if not defined JAVA_HOME (
    where java >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] Java not found. Install JDK 17 or newer
        echo         and/or set the JAVA_HOME environment variable.
        pause
        exit /b 1
    )
)

:run
call gradlew.bat :app:run %*

if errorlevel 1 (
    echo.
    echo [ERROR] Gradle run failed. See output above.
)

echo.
echo Press R to restart the app, or Q to quit.
choice /c RQ /n >nul
if errorlevel 2 goto :done
echo.
goto :run

:done
endlocal
