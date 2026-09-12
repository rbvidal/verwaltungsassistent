@echo off
setlocal EnableExtensions
chcp 65001 >/dev/null
title Verwaltungsassistent Demo App (port 8081)
set "REPO=%~dp0"
set "INACTIVITY_TIMEOUT_MINUTES=6"
pushd "%REPO%verwaltungsassistent-web"
call mvn spring-boot:run -Dspring-boot.run.profiles=demo "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8" > "%REPO%demo-app-8081.log" 2>&1
popd
