@echo off
chcp 65001 >nul
cd /d "%~dp0.."

set JAVA_OPTS=-Xms512m -Xmx2g
set JAVA_OPTS=%JAVA_OPTS% -Dfile.encoding=UTF-8
set JAVA_OPTS=%JAVA_OPTS% -Duser.timezone=Asia/Shanghai
set JAVA_OPTS=%JAVA_OPTS% -Dskiko.renderApi=SOFTWARE
set JAVA_OPTS=%JAVA_OPTS% -Dskiko.hardwareAcceleration=false

java %JAVA_OPTS% -jar lib\dynamic-bot-1.6.jar
pause