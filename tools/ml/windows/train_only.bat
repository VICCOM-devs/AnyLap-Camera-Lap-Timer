@echo off
rem Train (or continue training) on the cars rendered so far, then write the results page.
rem   C:\AnyLap\tools\ml\windows\train_only.bat [steps, default 25000] [run name, default v1]
setlocal
set ML=C:\anylap-ml
set REPO=C:\AnyLap
set STEPS=%1
if "%STEPS%"=="" set STEPS=25000
set RUN=%2
if "%RUN%"=="" set RUN=v1
%ML%\venv\Scripts\python %REPO%\tools\ml\train\train.py --sprites %ML%\sprites --photos %ML%\grounds --out %ML%\runs\%RUN% --steps %STEPS% --workers 6
%ML%\venv\Scripts\python %REPO%\tools\ml\train\report.py %ML%\runs\%RUN% %ML%\sprites --photos %ML%\grounds
start "" %ML%\runs\%RUN%\report.html
endlocal
