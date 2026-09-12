@echo off
REM ─────────────────────────────────────────────────────────────
REM Restore the bundled demo data set into the Docker stack
REM (compose.yaml): PostgreSQL dump, Neo4j graph dump, Qdrant
REM vector snapshot and the upload storage files.
REM
REM   deploy\restore-demo-data.bat
REM
REM After the restore, DEMO_STARTUP_RESET is set to false in .env so the
REM restored state survives restarts, and the whole stack is started.
REM (--embedded: used by install-windows.bat — no final pause)
REM ─────────────────────────────────────────────────────────────
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
title Verwaltungsassistent - restore demo data
set "EMBEDDED="
if /i "%~1"=="--embedded" set "EMBEDDED=1"
pushd "%~dp0.."

set "BACKUP_ARCHIVE=deploy\demo-data\verwaltungsassistent-demo-backup-2026-09-07.tar.gz"
set "BACKUP_NAME=verwaltungsassistent-demo-backup-2026-09-07"
set "PG_CONTAINER=va-postgres"
set "NEO4J_IMAGE=neo4j:5.26-community"
set "NEO4J_VOLUME=va_neo4j_data"
set "UPLOADS_VOLUME=va_uploads"
set "QDRANT_COLLECTION=mda_chunks"
REM defaults, overridable via .env
set "APP_PORT=8081"
set "QDRANT_PORT=6333"
set "POSTGRES_DB=verwaltungsassistent"
set "POSTGRES_USER=verwaltungsassistent"

if not exist ".env" copy /y ".env.example" ".env" >nul
for %%K in (APP_PORT QDRANT_PORT POSTGRES_DB POSTGRES_USER) do (
    for /f "usebackq tokens=1,* delims==" %%a in (`findstr /b /c:"%%K=" ".env" 2^>nul`) do set "%%a=%%b"
)

docker info >nul 2>&1 || (echo [ERROR] Docker Desktop is not running - start it first. & call :halt 1)
if not exist "%BACKUP_ARCHIVE%" (echo [ERROR] Backup archive not found: %BACKUP_ARCHIVE% & call :halt 1)

echo.
echo [1/7] Starting PostgreSQL, Qdrant and Neo4j ...
docker compose up -d --wait postgres qdrant neo4j || (echo [ERROR] Could not start the databases & call :halt 1)

echo [2/7] Extracting backup archive ...
set "TMPD=%TEMP%\verwaltungsassistent-restore-%RANDOM%%RANDOM%"
mkdir "%TMPD%" >nul 2>&1
tar -xzf "%BACKUP_ARCHIVE%" -C "%TMPD%" "%BACKUP_NAME%/postgres/verwaltungsassistent.dump" "%BACKUP_NAME%/neo4j/neo4j.dump" "%BACKUP_NAME%/qdrant" || (echo [ERROR] Extraction failed & call :halt 1)

echo [3/7] Verifying checksums and restoring upload storage ...
docker run --rm -v "%CD%\deploy\demo-data":/archive:ro -v %UPLOADS_VOLUME%:/target alpine sh -c "apk add --no-cache coreutils >/dev/null 2>&1; mkdir -p /b && tar -xzf /archive/%BACKUP_NAME%.tar.gz -C /b && cd /b/%BACKUP_NAME% && grep -v './SHA256SUMS$' SHA256SUMS | sha256sum -c --quiet - && cp -a storage/. /target/ && chown -R 1001:1001 /target && echo STORAGE_OK" > "%TMPD%\storage.log" 2>&1
findstr /c:"STORAGE_OK" "%TMPD%\storage.log" >nul || (echo [ERROR] Checksum verification / storage restore failed: & type "%TMPD%\storage.log" & call :halt 1)

echo [4/7] Restoring PostgreSQL ...
docker compose stop app >nul 2>&1
docker exec %PG_CONTAINER% psql -U %POSTGRES_USER% -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS \"%POSTGRES_DB%\";" -c "CREATE DATABASE \"%POSTGRES_DB%\" OWNER \"%POSTGRES_USER%\";" || (echo [ERROR] Database reset failed & call :halt 1)
docker exec -i %PG_CONTAINER% pg_restore -U %POSTGRES_USER% -d %POSTGRES_DB% --no-owner --no-privileges < "%TMPD%\%BACKUP_NAME%\postgres\verwaltungsassistent.dump" || (echo [ERROR] pg_restore failed & call :halt 1)

echo [5/7] Restoring Neo4j graph (offline load) ...
docker compose stop neo4j >nul 2>&1
docker run --rm -v %NEO4J_VOLUME%:/data -v "%TMPD%\%BACKUP_NAME%\neo4j:/backup:ro" %NEO4J_IMAGE% neo4j-admin database load neo4j --from-path=/backup --overwrite-destination=true || (echo [ERROR] Neo4j load failed & call :halt 1)
docker compose start neo4j >nul

echo [6/7] Restoring Qdrant vector snapshot ...
set "SNAP="
for %%f in ("%TMPD%\%BACKUP_NAME%\qdrant\*.snapshot") do set "SNAP=%%~ff"
if "%SNAP%"=="" (echo [ERROR] No snapshot file found & call :halt 1)
curl.exe -s -X DELETE "http://localhost:%QDRANT_PORT%/collections/%QDRANT_COLLECTION%" >nul 2>&1
curl.exe -s -X POST "http://localhost:%QDRANT_PORT%/collections/%QDRANT_COLLECTION%/snapshots/upload?priority=snapshot&wait=true" -F "snapshot=@%SNAP%" >nul || (echo [ERROR] Snapshot upload failed & call :halt 1)
curl.exe -s "http://localhost:%QDRANT_PORT%/collections/%QDRANT_COLLECTION%" | findstr /c:"\"points_count\":0" >nul && (echo [ERROR] Qdrant collection is empty after restore & call :halt 1)
curl.exe -s "http://localhost:%QDRANT_PORT%/collections/%QDRANT_COLLECTION%" | findstr /c:"\"points_count\":" >nul || (echo [ERROR] Qdrant collection not reachable & call :halt 1)

echo [7/7] Preserving the restored state and starting the application ...
powershell -NoProfile -ExecutionPolicy Bypass -File "deploy\env-set.ps1" -File ".env" -Key DEMO_STARTUP_RESET -Value false
docker compose up -d || (echo [ERROR] Could not start the stack & call :halt 1)

set /a TRIES=0
:waitloop
set /a TRIES+=1
curl.exe -s -o nul --max-time 3 "http://localhost:%APP_PORT%/login"
if not errorlevel 1 goto ready
if %TRIES% GEQ 80 (echo [ERROR] Application did not become ready & call :halt 1)
timeout /t 3 /nobreak >nul
goto waitloop

:ready
echo.
echo ================================================================
echo   Verwaltungsassistent is running:  http://localhost:%APP_PORT%
echo.
echo   Sign-in:
echo     admin@verwaltungsassistent.local          / admin123       (ADMIN)
echo     superadmin@verwaltungsassistent.local     / NcDn++2026$$  (maintenance)
echo     user@verwaltungsassistent.local           / user1234      (USER)
echo     demo01..demo20@verwaltungsassistent.local / demo1234      (demo staff)
echo ================================================================
echo.
rmdir /s /q "%TMPD%" >nul 2>&1
popd
if not defined EMBEDDED pause
exit /b 0

:halt
popd
if not defined EMBEDDED pause
exit /b %1
