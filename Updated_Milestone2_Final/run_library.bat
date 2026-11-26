
@echo off
cd /d "%~dp0"

echo Compiling Library Management System...
javac -cp ".;mysql-connector-j-26.10.0.jar" Main.java

if errorlevel 1 (
    echo.
    echo Compilation failed. Check the error messages above.
    pause
    exit /b 1
)

echo.
echo Starting Library Management System...
java -cp ".;mysql-connector-j-26.10.0.jar" Main

echo.
pause