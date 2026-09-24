@echo off
chcp 65001 > nul
"C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot\bin\java.exe" -Xms1G -Xmx2G -jar server.jar nogui
pause
