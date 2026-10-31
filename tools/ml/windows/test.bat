@echo off
rem AnyLap recognition model - quick test of the whole chain (~5-8 min): render 4 cars, train 200 steps, results page.
rem Writes to C:\anylap-ml\sprites-test and C:\anylap-ml\runs\test only - the real run is not touched.
rem   C:\AnyLap\tools\ml\windows\test.bat
setlocal
set ML=C:\anylap-ml
set REPO=C:\AnyLap
set BLENDER="C:\Program Files\Blender Foundation\Blender 4.5\blender.exe"
cd /d %REPO%
git pull

echo.
echo === TEST 1/3  Rendering 4 cars ===
set PYTHONPATH=%ML%\blender-libs
%BLENDER% -b --python-use-system-env -P %REPO%\tools\ml\sprites\render_sprites.py -- --out %ML%\sprites-test --cars 4 --samples 12
set PYTHONPATH=

echo.
echo === TEST 2/3  Training 200 steps ===
if exist %ML%\runs\test rmdir /s /q %ML%\runs\test
%ML%\venv\Scripts\python %REPO%\tools\ml\train\train.py --sprites %ML%\sprites-test --photos %ML%\grounds --out %ML%\runs\test --steps 200 --eval_every 100 --workers 6
if errorlevel 1 ( echo TEST FAILED in training - copy the lines above to Claude. & pause & exit /b 1 )

echo.
echo === TEST 3/3  Results page ===
%ML%\venv\Scripts\python %REPO%\tools\ml\train\report.py %ML%\runs\test %ML%\sprites-test --photos %ML%\grounds --races 5
if errorlevel 1 ( echo TEST FAILED in the report - copy the lines above to Claude. & pause & exit /b 1 )
start "" %ML%\runs\test\report.html
echo.
echo TEST OK. The numbers on the page mean nothing with 4 cars - it only proves the chain works.
echo Start the real run:  C:\AnyLap\tools\ml\windows\run.bat 13 30000
pause
endlocal
