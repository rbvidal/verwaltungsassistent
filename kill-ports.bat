@echo off
for %%p in (8080 8081) do (
    for /f "tokens=5" %%a in ('netstat -ano ^| findstr :%%p') do (
        taskkill /F /PID %%a 2>nul && echo Killed process on port %%p (PID %%a) || echo No process found on port %%p
    )
)
