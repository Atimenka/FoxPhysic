@echo off
rem Dedicated Windows server for FoxPhys 0.1.1-beta-release (Minecraft 1.21.1, Fabric).
rem This script is for Fabric only. The server folder contains fabric-server-launch.jar,
rem eula.txt (eula=true only after you accept it), server.properties, and mods\ with
rem fabric-api-0.116.6+1.21.1.jar and foxphys-fabric-0.1.1-beta-release.jar.
rem Requires Java 21 (java -version must say 21).
cd /d "%~dp0"
java -Xms2G -Xmx4G -jar fabric-server-launch.jar nogui
pause
