#!/bin/sh
# Mac/Linux version of run_library.bat

cd "$(dirname "$0")" || exit 1

echo "Compiling Library Management System..."
if ! javac -cp ".:mysql-connector-j-26.10.0.jar" Main.java; then
    echo
    echo "Compilation failed. Check the error messages above."
    exit 1
fi

echo
echo "Starting Library Management System..."
java -cp ".:mysql-connector-j-26.10.0.jar" Main
