@echo off

echo =========================
echo Running tests
echo =========================

call mvn clean test -B
if %errorlevel% neq 0 (
    echo Tests failed
    exit /b 1
)

echo =========================
echo Running Maven release
echo =========================

call mvn -B release:prepare release:perform
if %errorlevel% neq 0 (
    echo Release failed
    exit /b 1
)

echo =========================
echo Release complete
echo =========================
