@echo off
rem Dedicated Windows server for FoxPhys 0.1.0-alpha.5 test (Minecraft 1.21.1, Fabric).
rem Layout: this folder contains fabric-server-launch.jar, eula.txt (eula=true, after you accept it),
rem server.properties, and mods\ with fabric-api-0.116.6+1.21.1.jar and foxphys-0.1.0-alpha.5-windows-x64.jar.
rem Requires Java 21 (java -version must say 21).
cd /d "%~dp0"
java -Xms2G -Xmx4G -jar fabric-server-launch.jar nogui
pause
