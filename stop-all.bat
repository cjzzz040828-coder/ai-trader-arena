@echo off
title aiTrade Stop All

echo Killing services on ports 8000 / 8080 / 5173 / 5174

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8000 " ^| findstr LISTENING') do (
    echo Killing PID %%a on 8000
    taskkill /F /PID %%a >nul 2>&1
)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080 " ^| findstr LISTENING') do (
    echo Killing PID %%a on 8080
    taskkill /F /T /PID %%a >nul 2>&1
)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5173 " ^| findstr LISTENING') do (
    echo Killing PID %%a on 5173
    taskkill /F /PID %%a >nul 2>&1
)
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5174 " ^| findstr LISTENING') do (
    echo Killing PID %%a on 5174
    taskkill /F /PID %%a >nul 2>&1
)

REM Close the aiTrade windows themselves
taskkill /FI "WINDOWTITLE eq aiTrade*" /T /F >nul 2>&1

echo Done
timeout /t 2 /nobreak >nul
