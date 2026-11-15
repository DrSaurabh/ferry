@echo off
setlocal
title Ferry
cd /d "%~dp0"
if not exist "ferry.py" (
  echo.
  echo   Ferry files were not found next to Ferry.bat.
  echo   If you opened the zip, extract it first: right-click the zip, then Extract All.
  echo.
  pause
  exit /b 1
)
set "PY="
where py >nul 2>nul
if %errorlevel%==0 (
  py -3 -c "import sys; sys.exit(0 if sys.version_info>=(3,8) else 3)" >nul 2>nul
  if not errorlevel 1 set "PY=py -3"
)
if not defined PY (
  python -c "import sys; sys.exit(0 if sys.version_info>=(3,8) else 3)" >nul 2>nul
  if not errorlevel 1 set "PY=python"
)
if not defined PY (
  echo.
  echo   Python 3.8 or newer was not found.
  echo   Install it from https://www.python.org/downloads/ and tick "Add python.exe to PATH".
  echo.
  pause
  exit /b 1
)
%PY% ferry.py %*
if errorlevel 1 pause
endlocal
