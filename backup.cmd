@echo off
setlocal

rem  Latch - full-history backup.
rem
rem  Double-click this. It writes a git bundle holding EVERY ref and the whole
rem  history to C:\dev\latch-backup, verifies it, and tells you which file to
rem  put in Google Drive.
rem
rem  A bundle is a single file that `git clone` can clone from, so it is a real
rem  backup rather than a copy of some files: restoring needs nothing but git
rem  and this one file. Nothing here touches any account or any remote.

set "REPO=%~dp0"
set "DEST=C:\dev\latch-backup"

for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyy-MM-dd-HHmm"') do set "STAMP=%%i"
set "FILE=%DEST%\latch-android-%STAMP%.bundle"

if not exist "%DEST%" mkdir "%DEST%"

echo.
echo Backing up  %REPO%
echo To          %FILE%
echo.

rem  Written under a temporary name and moved into place, so a run that fails
rem  half way leaves no file at all rather than a truncated one somebody would
rem  later trust.
git -C "%REPO%" bundle create "%FILE%.part" --all HEAD
if errorlevel 1 goto failed

git -C "%REPO%" bundle verify "%FILE%.part"
if errorlevel 1 goto failed

move /y "%FILE%.part" "%FILE%" >nul
if errorlevel 1 goto failed

echo.
echo ================================================================
echo   Backup written and verified.
echo.
echo   %FILE%
echo.
echo   Drag that file into Google Drive.
echo ================================================================
echo.
pause
exit /b 0

:failed
echo.
echo ================================================================
echo   BACKUP FAILED. Nothing was written.
echo   Do not assume an earlier bundle in %DEST% is current.
echo ================================================================
echo.
if exist "%FILE%.part" del "%FILE%.part"
pause
exit /b 1
