@echo off
rem AnyLap recognition model - export a trained run for the phone (ONNX + LiteRT .tflite) and print the anylap_models row.
rem   C:\AnyLap\tools\ml\windows\export.bat <run name, e.g. effnet_lite0_1h> [version, default 1]
rem Output: C:\anylap-ml\export\v<version>\carnet.tflite + carnet.json (sha256, bytes). Upload the .tflite to a GitHub
rem release of the repo (tag model-v<version>) and insert the row the script prints (see tools\ml\README.md, step 5).
setlocal
set ML=C:\anylap-ml
set REPO=C:\AnyLap
set RUN=%1
if "%RUN%"=="" ( echo Usage: export.bat ^<run name^> [version] & exit /b 1 )
set VER=%2
if "%VER%"=="" set VER=1
cd /d %REPO%
title AnyLap - export %RUN% v%VER%
echo Making sure ai-edge-torch (LiteRT export) is installed ...
%ML%\venv\Scripts\python -m pip install -q ai-edge-torch
echo.
echo === Exporting %ML%\runs\%RUN%\best.pt  (started %TIME:~0,5%) ===
%ML%\venv\Scripts\python %REPO%\tools\ml\export\export.py %ML%\runs\%RUN%\best.pt --out %ML%\export\v%VER% --version %VER%
if errorlevel 1 ( echo Export stopped with an error - copy the lines above to Claude. & pause & exit /b 1 )
echo.
echo === Done. Files in %ML%\export\v%VER% ===
dir %ML%\export\v%VER%
echo.
echo Next: upload carnet.tflite to a GitHub release (tag model-v%VER%), then run the SQL from carnet.json (sha256, bytes):
echo   insert into public.anylap_models (name, platform, version, url, sha256, bytes, min_app_build) values
echo   ('signature', 'android', %VER%, 'https://github.com/VICCOM-devs/AnyLap-Camera-Lap-Timer/releases/download/model-v%VER%/carnet.tflite', '<sha256>', <bytes>, 1008);
start "" %ML%\export\v%VER%
pause
