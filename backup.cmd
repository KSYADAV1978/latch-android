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
rem
rem  BUT A BUNDLE HOLDS GIT OBJECTS AND NOTHING ELSE, so anything git-ignored is
rem  not in it. That is not a detail: FR-1222's business-card corpus is deliberately
rem  git-ignored (SRS 1.155, it holds real people's contact details and must never
rem  be published), which means the repository backup would have restored everything
rem  EXCEPT the one file that exists nowhere but this machine. It is copied
rem  separately below. CLAUDE.md claimed the bundle covered it; it never could.

rem  `%~dp0` ends with a backslash, which would escape the closing quote of
rem  "%REPO%" and hand git one long mangled argument. Trimmed once, here.
set "REPO=%~dp0"
set "REPO=%REPO:~0,-1%"
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

rem  FR-1222's corpus, which the bundle above cannot contain. Copied under the same
rem  timestamp so a bundle and a corpus from one run are obviously a pair.
rem
rem  Its absence is a normal state and not a failure: a fresh clone has none, and
rem  only this machine ever has. So it is reported either way rather than skipped in
rem  silence -- a backup that quietly omits a file is how the file comes to be lost.
set "CORPUS=%REPO%\cards\src\test\resources\cards\card_corpus.tsv"
set "CORPUSFILE=%DEST%\latch-card-corpus-%STAMP%.tsv"
set "CORPUSDONE=no"

if exist "%CORPUS%" (
  copy /y "%CORPUS%" "%CORPUSFILE%" >nul
  if errorlevel 1 goto failed
  set "CORPUSDONE=yes"
)

echo.
echo ================================================================
echo   Backup written and verified.
echo.
echo   %FILE%
echo     The whole repository history. Safe to store anywhere.
echo.
if "%CORPUSDONE%"=="yes" (
  echo   %CORPUSFILE%
  echo     FR-1222's business-card corpus. NOT in the bundle - a bundle holds
  echo     git objects and this file is git-ignored on purpose.
  echo.
  echo     IT CONTAINS REAL PEOPLE'S NAMES, DIRECT LINES AND WORK ADDRESSES,
  echo     off cards handed over in confidence. It is irreplaceable, so it needs
  echo     a backup; it is personal data, so where you put it is a decision and
  echo     not a habit. Never publish it, never attach it to an issue, and keep
  echo     it out of any folder you share with anyone.
) else (
  echo   No card corpus on this machine, so none was copied.
  echo     Expected on a fresh clone. On the developer's machine it means
  echo     FR-1222's corpus is missing and the bundle will not bring it back.
)
echo.
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
if exist "%CORPUSFILE%" del "%CORPUSFILE%"
pause
exit /b 1
