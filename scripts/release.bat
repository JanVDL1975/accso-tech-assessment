cd /d %~dp0..

@echo off
setlocal enabledelayedexpansion

echo =========================
echo Pre-release checks
echo =========================

REM Ensure clean working tree
git status --porcelain > temp.txt
for %%A in (temp.txt) do set SIZE=%%~zA
del temp.txt

if not "%SIZE%"=="0" (
    echo ✔ Changes detected - proceeding
) else (
    echo ✔ No changes detected. Nothing to release.
    exit /b 0
)

echo =========================
echo Running tests
echo =========================

call mvn clean test -B
if %errorlevel% neq 0 (
    echo ❌ Tests failed
    exit /b 1
)

echo =========================
echo Running Maven release
echo =========================

call mvn -B release:prepare release:perform
if %errorlevel% neq 0 (
    echo ❌ Release failed
    exit /b 1
)

echo =========================
echo ✔ Release complete
echo =========================

endlocal