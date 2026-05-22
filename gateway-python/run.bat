@echo off
title aiTrade Python Gateway 8000

set PYTHON_EXE=D:/pythonSoftware/python3.11.9/python.exe

cd /d %~dp0

if not exist "%PYTHON_EXE%" (
    echo ERROR Python 3.11 not found at %PYTHON_EXE%
    pause
    exit /b 1
)

REM Pre-clean: kill any process holding port 8000
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8000 " ^| findstr LISTENING') do (
    echo Killing leftover PID %%a on port 8000
    taskkill /F /T /PID %%a >nul 2>&1
)

if not exist venv (
    echo Creating venv with Python 3.11
    "%PYTHON_EXE%" -m venv venv
    call venv/Scripts/activate.bat
    python -m pip install --upgrade pip -i https://pypi.tuna.tsinghua.edu.cn/simple
    pip install -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt
) else (
    call venv/Scripts/activate.bat
)

if not exist logs mkdir logs

echo.
echo ========================================
echo  Python Gateway starting
echo  URL:  http://localhost:8000
echo  Docs: http://localhost:8000/docs
echo ========================================
echo.

python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

pause
