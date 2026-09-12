@echo off
setlocal EnableExtensions
chcp 65001 >nul
title Verwaltungsassistent Demo Starter
set "REPO=%~dp0"
set "DOCKER_EXE=C:\Program Files\Docker\Docker\Docker Desktop.exe"
set "OLLAMA_DIR=%LOCALAPPDATA%\Programs\Ollama"
set "APP_PORT=8081"
set "INACTIVITY_TIMEOUT_MINUTES=6"

echo.
echo ================================================================
echo   Verwaltungsassistent Verwaltungsassistent - Demo Start (German demo)
echo ================================================================
echo.

REM ---------- tool checks ----------
where mvn >nul 2>&1 || (echo [ERROR] Maven mvn not found in PATH & pause & exit /b 1)
where curl >nul 2>&1 || (echo [ERROR] curl not found in PATH & pause & exit /b 1)
where docker >nul 2>&1 || (echo [ERROR] Docker CLI not found in PATH & pause & exit /b 1)

REM ---------- 1. Docker Desktop ----------
echo [1/5] Docker Desktop ...
docker info >nul 2>&1
if errorlevel 1 (
    echo        starting Docker Desktop - this can take a minute ...
    if exist "%DOCKER_EXE%" (
        start "" "%DOCKER_EXE%"
    ) else (
        echo [ERROR] Docker Desktop not found at: %DOCKER_EXE%
        pause & exit /b 1
    )
    call :wait_docker
    if errorlevel 1 ( echo [ERROR] Docker did not become ready & pause & exit /b 1 )
) else (
    echo        already running
)

REM ---------- 2. PostgreSQL + Neo4j + Qdrant ----------
echo [2/5] PostgreSQL + Neo4j + Qdrant containers ...
docker compose -f "%REPO%docker-compose.yml" up -d postgres
docker start mda-neo4j mda-qdrant >nul 2>&1
if errorlevel 1 (
    echo        qdrant/neo4j containers missing - creating them from docker-compose-prod.yml ...
    docker compose -f "%REPO%docker-compose-prod.yml" up -d qdrant neo4j
)
call :wait_postgres
call :wait_http "Neo4j   " http://localhost:7474
call :wait_http "Qdrant  " http://localhost:6333/collections

REM ---------- 3. Ollama ----------
echo [3/5] Ollama ...
curl -s -o nul --max-time 3 http://localhost:11434/api/tags
if errorlevel 1 (
    echo        starting Ollama ...
    if exist "%OLLAMA_DIR%\Ollama app.exe" start "" "%OLLAMA_DIR%\Ollama app.exe"
    if exist "%OLLAMA_DIR%\ollama.exe" start "" /min "%OLLAMA_DIR%\ollama.exe" serve
    call :wait_http "Ollama" http://localhost:11434/api/tags
) else (
    echo        already running
)

REM ---------- 4. model warm-up (loads models into VRAM, 30 min) ----------
echo [4/5] Warming up models - first request after idle is slow ...
curl -s --max-time 300 -o nul http://localhost:11434/api/chat -d "{\"model\":\"qwen2.5:7b\",\"messages\":[{\"role\":\"user\",\"content\":\"ok\"}],\"stream\":false,\"keep_alive\":\"30m\"}"
curl -s --max-time 300 -o nul http://localhost:11434/api/chat -d "{\"model\":\"qwen2.5:14b\",\"messages\":[{\"role\":\"user\",\"content\":\"ok\"}],\"stream\":false,\"keep_alive\":\"30m\"}"
curl -s --max-time 120 -o nul http://localhost:11434/api/embeddings -d "{\"model\":\"nomic-embed-text\",\"prompt\":\"warmup\",\"keep_alive\":\"30m\"}"
echo        models warm

REM ---------- 5. build latest code + start the app ----------
echo [5/5] Building latest code ...
netstat -ano | findstr ":%APP_PORT% " | findstr LISTENING >nul 2>&1
if not errorlevel 1 (
    echo [ERROR] Port %APP_PORT% is already in use - an old app instance is still running.
    echo         Close the "Verwaltungsassistent Demo App" window or kill the process, then run this script again.
    pause & exit /b 1
)
pushd "%REPO%"
call mvn -pl verwaltungsassistent-web -am install -DskipTests -q
if errorlevel 1 (
    popd
    echo [ERROR] Build failed - see Maven output above
    pause & exit /b 1
)
popd
echo        starting the application on port %APP_PORT% ...
echo        a new window opens with the app log - close it to stop the app
REM The whole cmd /k command must be quoted: an unquoted "&" would run
REM "mvn" in THIS window from the repo root and fail on platform-common
REM ("Unable to find a suitable main class").
start "Verwaltungsassistent Demo App (port %APP_PORT%)" /d "%REPO%verwaltungsassistent-web" cmd /k "chcp 65001 >nul & mvn spring-boot:run -Dspring-boot.run.profiles=demo "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8""
call :wait_http "App    " http://localhost:%APP_PORT%/login
start http://localhost:%APP_PORT%

echo.
echo ================================================================
echo   Demo ready:  http://localhost:%APP_PORT%
echo   Stop: close the "Verwaltungsassistent Demo App" window
echo ================================================================
echo.
pause
exit /b 0

REM ================= subroutines =================

:wait_docker
for /l %%i in (1,1,60) do (
    docker info >nul 2>&1
    if not errorlevel 1 ( echo        Docker ready & goto :eof )
    timeout /t 5 /nobreak >nul
)
echo        Docker did not become ready in 5 minutes
exit /b 1

:wait_postgres
for /l %%i in (1,1,60) do (
    for /f "tokens=*" %%s in ('docker inspect --format "{{.State.Health.Status}}" va-postgres 2^>nul') do (
        if "%%s"=="healthy" ( echo        Postgres ready & goto :eof )
    )
    timeout /t 5 /nobreak >nul
)
echo        [WARN] Postgres did not become healthy in 5 minutes
exit /b 0

:wait_http
REM %1 = label, %2 = url
for /l %%i in (1,1,60) do (
    curl -s -o nul --max-time 3 "%~2" >nul 2>&1
    if not errorlevel 1 ( echo        %~1 ready & goto :eof )
    timeout /t 5 /nobreak >nul
)
echo        [WARN] %~1 did not become ready in 5 minutes
exit /b 0
