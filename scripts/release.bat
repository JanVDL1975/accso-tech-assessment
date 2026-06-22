@echo off
setlocal enabledelayedexpansion

echo =========================
echo Running tests
echo =========================

call mvn clean test -B
if %errorlevel% neq 0 (
    echo Tests failed
    exit /b 1
)

echo =========================
echo Deriving versions from pom
echo =========================

for /f "delims=" %%v in ('mvn help:evaluate -Dexpression=project.version -q -DforceStdout') do set CURRENT_VERSION=%%v
echo Current version: %CURRENT_VERSION%

:: Strip -SNAPSHOT to get release version (e.g. 1.0.0-SNAPSHOT -> 1.0.0)
set RELEASE_VERSION=%CURRENT_VERSION:-SNAPSHOT=%
echo Release version: %RELEASE_VERSION%

:: Bump patch for next dev version (e.g. 1.0.0 -> 1.0.1-SNAPSHOT)
for /f "tokens=1,2,3 delims=." %%a in ("%RELEASE_VERSION%") do (
    set /a PATCH=%%c + 1 >nul
    set DEV_VERSION=%%a.%%b.!PATCH!-SNAPSHOT
)
echo Next dev version: %DEV_VERSION%

echo =========================
echo Running Maven release
echo =========================

call mvn -B release:prepare release:perform ^
    -DreleaseVersion=%RELEASE_VERSION% ^
    -DdevelopmentVersion=%DEV_VERSION% ^
    -Dtag=v%RELEASE_VERSION%
if %errorlevel% neq 0 (
    echo Release failed
    exit /b 1
)

echo =========================
echo Release complete
echo =========================
