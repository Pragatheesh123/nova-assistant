@echo off
setlocal
title Nova
cd /d "%~dp0"

echo.
echo   N O V A
echo.

rem --- 1. Make sure the zip was extracted -----------------------------------
if not exist "server\main.py" goto :not_extracted

rem --- 2. Find Python ------------------------------------------------------
set "PY="
py -3 -c "import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)" >nul 2>&1 && set "PY=py -3"
if not defined PY python -c "import sys; sys.exit(0 if sys.version_info >= (3, 10) else 1)" >nul 2>&1 && set "PY=python"
if not defined PY goto :no_python

rem --- 3. First run: create the private Python environment and install -----
if exist ".venv\installed.ok" goto :run
echo   First run: installing what Nova needs. This takes a few minutes...
echo.
if exist ".venv" rmdir /s /q ".venv"
%PY% -m venv .venv
if errorlevel 1 goto :venv_failed
".venv\Scripts\python.exe" -m pip install --upgrade pip
".venv\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 goto :install_failed
echo.
echo   Installing local speech recognition (optional)...
".venv\Scripts\python.exe" -m pip install faster-whisper
if errorlevel 1 echo   [!] Speech recognition didn't install. Nova will use the browser's speech instead.
".venv\Scripts\python.exe" -m pip install pycaw >nul 2>&1
echo ok> ".venv\installed.ok"

:run
echo.
echo   Starting Nova. Keep this window open (you can minimise it).
echo   Nova's own window opens in a few seconds. Ctrl+Alt+N calls Nova from anywhere.
echo.
".venv\Scripts\python.exe" -m pip install -q pynput >nul 2>&1
".venv\Scripts\python.exe" -m server.main --window %*
echo.
echo   Nova stopped. If you see an error above, send a screenshot of it.
goto :end

:not_extracted
echo   [!] It looks like Nova is being run from inside the zip file.
echo.
echo   Fix: right-click nova.zip, choose "Extract All...", open the extracted
echo   "nova" folder and double-click start.bat there.
goto :end

:no_python
echo   [!] Python 3.10 or newer was not found.
echo.
echo   Fix:
echo     1. Go to https://www.python.org/downloads/ and download Python.
echo     2. In the installer, TICK the box "Add python.exe to PATH" at the bottom.
echo     3. Click Install Now, then double-click start.bat again.
echo.
echo   Opening the download page for you...
start https://www.python.org/downloads/
goto :end

:venv_failed
echo.
echo   [!] Couldn't set up Python's environment. Try reinstalling Python from
echo   python.org with "Add python.exe to PATH" ticked.
goto :end

:install_failed
echo.
echo   [!] Installing packages failed - see the red error above.
echo   Check your internet connection and run start.bat again.
if exist ".venv\installed.ok" del ".venv\installed.ok"
goto :end

:end
echo.
pause
