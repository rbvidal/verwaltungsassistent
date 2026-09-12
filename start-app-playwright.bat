@echo off
chcp 65001 >nul
title Verwaltungsassistent Playwright E2E (port 8081)
set "REPO=%~dp0"
pushd "%REPO%verwaltungsassistent-web"
call mvn -o spring-boot:run -Dspring-boot.run.profiles=demo,playwright "-Dspring-boot.run.jvmArguments=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -Dprocessing-times.baselines.gewerbeanmeldung=3 -Dprocessing-times.baselines.ummeldung=2 -Dprocessing-times.baselines.wohngeld=5 -Dprocessing-times.baselines.baugenehmigung=6 -Dprocessing-times.baselines.reisepass=4 -Dprocessing-times.baselines.geovorgang=5 -Dprocessing-times.baselines.allgemein=4" > "%REPO%e2e-app-8081.log" 2>&1
popd
