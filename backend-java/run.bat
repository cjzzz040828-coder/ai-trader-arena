@echo off
title aiTrade Java Backend 8080

cd /d %~dp0

set JAVA_HOME=D:/Idea/JDK17
set PATH=%JAVA_HOME%/bin;%PATH%

set MVN=D:/Idea/apache-maven-3.9.9-bin/apache-maven-3.9.9/bin/mvn.cmd

REM Pre-clean: kill any process holding port 8080
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8080 " ^| findstr LISTENING') do (
    echo Killing leftover PID %%a on port 8080
    taskkill /F /T /PID %%a >nul 2>&1
)

echo.
echo ========================================
echo  Java Backend starting
echo  URL:  http://localhost:8080
echo  Test: http://localhost:8080/api/test/gateway-health
echo ========================================
echo.

call "%MVN%" spring-boot:run -Dspring-boot.run.fork=false

pause
