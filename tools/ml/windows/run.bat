@echo off
rem AnyLap recognition model - the whole run: render cars, train, write the results page.
rem   C:\AnyLap\tools\ml\windows\run.bat [render hours, default 12] [training steps, default 25000]
rem Safe to stop (close the window) and start again: finished cars are kept, rendering continues where it stopped.
setlocal
set ML=C:\anylap-ml
set REPO=C:\AnyLap
set BLENDER="C:\Program Files\Blender Foundation\Blender 4.5\blender.exe"
set HOURS=%1
if "%HOURS%"=="" set HOURS=12
set STEPS=%2
if "%STEPS%"=="" set STEPS=25000

cd /d %REPO%
git pull
powercfg /change standby-timeout-ac 0 >nul 2>nul

echo.
echo === 1/3  Rendering cars for %HOURS% hours (one line per finished car) ===
set PYTHONPATH=%ML%\blender-libs
%BLENDER% -b --python-use-system-env -P %REPO%\tools\ml\sprites\render_sprites.py -- --out %ML%\sprites --cars 5000 --samples 12 --hours %HOURS%
set PYTHONPATH=

echo.
echo === 2/3  Training (%STEPS% steps) - progress and time left on each line ===
%ML%\venv\Scripts\python %REPO%\tools\ml\train\train.py --sprites %ML%\sprites --photos %ML%\grounds --out %ML%\runs\v1 --steps %STEPS% --workers 6
if errorlevel 1 ( echo Training stopped with an error - copy the lines above to Claude. & pause & exit /b 1 )

echo.
echo === 3/3  Results page ===
%ML%\venv\Scripts\python %REPO%\tools\ml\train\report.py %ML%\runs\v1 %ML%\sprites --photos %ML%\grounds
start "" %ML%\runs\v1\report.html
echo.
echo All done. Results: %ML%\runs\v1\report.html   Model: %ML%\runs\v1\best.pt
pause
endlocal
