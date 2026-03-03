@echo off
@title HeavenMS
REM Prefer JAVA_HOME (e.g., JDK 21) if set
if not "%JAVA_HOME%"=="" (
	set "PATH=%JAVA_HOME%\bin;%PATH%"
)

REM Support both legacy NetBeans dist/ layout and Maven target/ layout
set "CLASSPATH=.;dist\*;target\classes;target\dependency\*;target\*"
java -Xmx2048m -Dwzpath=wz\ net.server.Server
pause