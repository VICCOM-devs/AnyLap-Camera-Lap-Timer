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
echo === 2/4  Python ===
where py >nul 2>nul
if errorlevel 1 (
  echo Python is not installed. Installing Python 3.12 with winget ...
  winget install -e --id Python.Python.3.12 --accept-package-agreements --accept-source-agreements
  echo.
  echo Python installed. CLOSE this window, open a NEW Command Prompt and run setup.bat again.
  exit /b 0
)

echo.
echo === 3/4  Training environment (PyTorch with CUDA, ~3 GB download) ===
if not exist %ML%\venv\Scripts\python.exe (
  py -3.12 -m venv %ML%\venv 2>nul || py -m venv %ML%\venv
)
%ML%\venv\Scripts\python -m pip install --upgrade pip
%ML%\venv\Scripts\python -m pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124
%ML%\venv\Scripts\python -m pip install numpy pillow onnx

echo.
echo === 4/4  Check ===
%ML%\venv\Scripts\python -c "import torch; ok=torch.cuda.is_available(); print('GPU for training:', torch.cuda.get_device_name(0) if ok else 'NOT FOUND - training would run on the CPU (very slow)')"
echo.
echo Setup done. Optional: put 10-30 phone photos of real floors (track, asphalt, carpet, dirt ...) in %ML%\grounds
echo Then start the full run:   C:\AnyLap\tools\ml\windows\run.bat 12 25000
endlocal
