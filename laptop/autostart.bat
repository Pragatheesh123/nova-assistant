@echo off
rem Start Nova automatically when you sign in to Windows (runs quietly in the background).
rem Run this file again to turn it off.
setlocal
set "LINK=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\Nova.vbs"
if exist "%LINK%" (
  del "%LINK%"
  echo.
  echo   Nova will no longer start with Windows.
  echo.
  pause
  exit /b
)
> "%LINK%" echo Set sh = CreateObject("WScript.Shell")
>> "%LINK%" echo sh.CurrentDirectory = "%~dp0"
>> "%LINK%" echo sh.Run """%~dp0.venv\Scripts\pythonw.exe"" -m server.main", 0, False
echo.
echo   Done. Nova will start quietly every time you sign in.
echo   Press Ctrl+Alt+N anywhere to call it. Run autostart.bat again to turn this off.
echo.
pause
