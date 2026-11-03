@echo off
rem AnyLap recognition model - run 2: train on the cars already rendered, then the results page.
rem   C:\AnyLap\tools\ml\windows\train2.bat [hours, default 1] [backbone, default effnet_lite0] [run name, default the backbone]
rem   backbone: effnet_lite0 (pretrained on real photos, 96 px)  carnet (run 1's own network, 64 px)  mnv3_large  mnv3_small
rem Short runs first (1 h) to see the curve climb, then longer ones. Reports land in C:\anylap-ml\runs\<name>\report.html
setlocal
set ML=C:\anylap-ml
set REPO=C:\AnyLap
set HOURS=%1
if "%HOURS%"=="" set HOURS=1
set BB=%2
if "%BB%"=="" set BB=effnet_lite0
set RUN=%3
if "%RUN%"=="" set RUN=%BB%_%HOURS%h
cd /d %REPO%
title AnyLap - run 2 starting
git pull
echo.
echo Making sure timm (EfficientNet-Lite) is installed ...
%ML%\venv\Scripts\python -m pip install -q timm
echo.
echo === Training %BB% for about %HOURS% h  (started %TIME:~0,5%) ===
echo     a line every 100 steps, a CHECK on never-seen cars at every tenth of the run - watch "unseen cars named right" climb
%ML%\venv\Scripts\python %REPO%\tools\ml\train\train.py --sprites %ML%\sprites --photos %ML%\grounds --out %ML%\runs\%RUN% --backbone %BB% --hours %HOURS% --reuse 3 --workers 6
if errorlevel 1 ( echo Training stopped with an error - copy the lines above to Claude. & pause & exit /b 1 )
echo.
echo === Results page  (started %TIME:~0,5%) ===
%ML%\venv\Scripts\python %REPO%\tools\ml\train\report.py %ML%\runs\%RUN% %ML%\sprites --photos %ML%\grounds
start "" %ML%\runs\%RUN%\report.html
echo.
echo Done. Results: %ML%\runs\%RUN%\report.html  - drag it into the chat.
pause
endlocal
