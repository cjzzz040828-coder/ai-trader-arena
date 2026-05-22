@echo off
title aiTrade Frontend 5173

cd /d %~dp0

set PATH=D:/Node;D:/Node/node_global;%PATH%

if not exist node_modules (
    echo Installing npm dependencies first run takes about 2 min
    call D:/Node/npm.cmd install --registry=https://registry.npmmirror.com
)

echo.
echo ========================================
echo  Frontend starting
echo  URL: http://localhost:5173
echo ========================================
echo.

call D:/Node/npm.cmd run dev

pause
