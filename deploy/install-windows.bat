@echo off
REM ─────────────────────────────────────────────────────────────
REM One-shot installation of the Verwaltungsassistent on Windows
REM using Docker Desktop.
REM
REM   deploy\install-windows.bat
REM
REM Steps: start Docker Desktop if needed → build the application image →
REM start PostgreSQL, Qdrant, Neo4j and Ollama (models download in the
REM background) → restore the bundled demo data set → open the browser.
REM
REM If an Ollama is already running on the host (http://localhost:11434),
REM it is used instead of the containerized one — models are then not
REM downloaded again.
REM ─────────────────────────────────────────────────────────────
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
title Verwaltungsassistent - installation
pushd "%~dp0.."

set "DOCKER_EXE=C:\Program Files\Docker\Docker\Docker Desktop.exe"

echo.
echo ================================================================
echo   Verwaltungsassistent - Docker installation
echo ================================================================

REM ── Docker Desktop ──
docker info >nul 2>&1
if errorlevel 1 (
    echo [1/6] Starting Docker Desktop - this can take a minute ...
    if exist "%DOCKER_EXE%" (
        start "" "%DOCKER_EXE%"
    ) else (
        echo [ERROR] Docker Desktop not found. Install it first:
        echo         https://www.docker.com/products/docker-desktop/
        popd & pause & exit /b 1
    )
    for /l %%i in (1,1,60) do (
        docker info >nul 2>&1
        if not errorlevel 1 goto dockerready
        timeout /t 5 /nobreak >nul
    )
    echo [ERROR] Docker did not become ready within 5 minutes.
    popd & pause & exit /b 1
)
:dockerready
echo [1/6] Docker is ready.

REM ── Configuration ──
if not exist ".env" copy /y ".env.example" ".env" >nul
echo [2/6] Configuration: .env

REM ── Host Ollama? ──
curl.exe -s -o nul --max-time 3 http://localhost:11434/api/tags
if not errorlevel 1 (
    echo [3/6] Host Ollama detected on port 11434 - using it ^(no model download^).
    powershell -NoProfile -ExecutionPolicy Bypass -File "deploy\env-set.ps1" -File ".env" -Key COMPOSE_PROFILES -Value ""
    powershell -NoProfile -ExecutionPolicy Bypass -File "deploy\env-set.ps1" -File ".env" -Key OLLAMA_BASE_URL -Value "http://host.docker.internal:11434"
) else (
    echo [3/6] No host Ollama - the containerized Ollama downloads the models.
)

REM ── Build ──
echo [4/6] Building the application image ^(first build: several minutes^) ...
docker compose build app || (echo [ERROR] Build failed & popd & pause & exit /b 1)

REM ── Demo data + start (restore script starts infra, restores, starts app) ──
echo [5/6] Restoring the bundled demo data and starting the stack ...
call "deploy\restore-demo-data.bat" --embedded
if errorlevel 1 (echo [ERROR] Installation failed & popd & pause & exit /b 1)

set "APP_PORT=8081"
for /f "usebackq tokens=1,* delims==" %%a in (`findstr /b /c:"APP_PORT=" ".env" 2^>nul`) do set "APP_PORT=%%b"

echo [6/6] Opening the browser ...
start "" "http://localhost:%APP_PORT%"
echo.
echo Installation complete: http://localhost:%APP_PORT%
popd
pause
exit /b 0
