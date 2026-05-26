@echo off
title StratForge Stop All

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

REM Close the StratForge service windows themselves.
REM 严格匹配 start-all.bat 启动的三个窗口标题，避免把"碰巧标题含 StratForge"的其他 cmd
REM （比如在项目目录下运行的 Claude Code / 终端）一并干掉。
taskkill /FI "WINDOWTITLE eq StratForge-Python" /T /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq StratForge-Java" /T /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq StratForge-Frontend" /T /F >nul 2>&1

echo Done
timeout /t 2 /nobreak >nul
