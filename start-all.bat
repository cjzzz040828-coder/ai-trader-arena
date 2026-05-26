@echo off
title StratForge Start All

set ROOT=%~dp0

echo.
echo [1/3] Starting Python Gateway
start "StratForge-Python" cmd /k "%ROOT%gateway-python/run.bat"

echo Waiting 8 seconds for Python
timeout /t 8 /nobreak >nul

echo.
echo [2/3] Starting Java Backend
start "StratForge-Java" cmd /k "%ROOT%backend-java/run.bat"

echo Waiting 25 seconds for Java first run takes longer
timeout /t 25 /nobreak >nul

echo.
echo [3/3] Starting Frontend
start "StratForge-Frontend" cmd /k "%ROOT%frontend-vue/run.bat"

echo.
echo ============================================================
echo  All services launched in separate windows
echo    Python  http://localhost:8000/docs
echo    Java    http://localhost:8080/api/quote/gateway-health
echo    Web UI  http://localhost:5173
echo.
echo  Wait 10-20s for frontend then open the Web UI
echo ============================================================
echo.

pause
