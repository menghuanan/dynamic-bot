@echo off
cd /d "%~dp0.."
java -Xms512m -Xmx2g -jar lib\dynamic-bot-1.3.jar
pause