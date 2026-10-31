@echo off
rem AnyLap recognition model - one-time setup on Windows (run once, ~10 min).
rem   C:\AnyLap\tools\ml\windows\setup.bat
setlocal
set ML=C:\anylap-ml
set BPY="C:\Program Files\Blender Foundation\Blender 4.5\4.5\python\bin\python.exe"
if not exist %ML% mkdir %ML%
if not exist %ML%\grounds mkdir %ML%\grounds

echo.
echo === 1/4  Pillow for Blender (car liveries) ===
%BPY% -m pip install --upgrade pillow --target %ML%\blender-libs
if errorlevel 1 ( echo Could not install Pillow for Blender. Is Blender 4.5 in "C:\Program Files\Blender Foundation\Blender 4.5"? & exit /b 1 )

echo.
echo === 2/4  Python 3.12 (PyTorch needs it; other Python versions on the PC are left alone) ===
py -3.12 --version >nul 2>nul
if errorlevel 1 (
  echo Installing Python 3.12 with winget ...
  winget install -e --id Python.Python.3.12 --accept-package-agreements --accept-source-agreements
  py -3.12 --version >nul 2>nul
  if errorlevel 1 ( echo Python 3.12 installed - CLOSE this window, open a NEW Command Prompt and run setup.bat again. & exit /b 0 )
)

echo.
echo === 3/4  Training environment (PyTorch with CUDA, ~3 GB download) ===
if exist %ML%\venv\Scripts\python.exe (
  %ML%\venv\Scripts\python -c "import sys; sys.exit(0 if sys.version_info[:2]==(3,12) else 1)"
  if errorlevel 1 ( echo Old environment has the wrong Python - recreating it. & rmdir /s /q %ML%\venv )
)
if not exist %ML%\venv\Scripts\python.exe py -3.12 -m venv %ML%\venv
%ML%\venv\Scripts\python -m pip install --upgrade pip
%ML%\venv\Scripts\python -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu126
if errorlevel 1 ( echo PyTorch did not install - copy the lines above to Claude. & exit /b 1 )
%ML%\venv\Scripts\python -m pip install numpy pillow onnx
if errorlevel 1 ( echo numpy/pillow/onnx did not install - copy the lines above to Claude. & exit /b 1 )

echo.
echo === 4/4  Check ===
%ML%\venv\Scripts\python -c "import torch; ok=torch.cuda.is_available(); print('GPU for training:', torch.cuda.get_device_name(0) if ok else 'NOT FOUND - training would run on the CPU (very slow)')"
if errorlevel 1 ( echo PyTorch check failed - copy the lines above to Claude. & exit /b 1 )
echo.
echo Setup done. Optional: put 10-30 phone photos of real floors (track, asphalt, carpet, dirt ...) in %ML%\grounds
echo Then start the full run:   C:\AnyLap\tools\ml\windows\run.bat 12 25000
endlocal
