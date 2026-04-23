@echo off
cd /d "%~dp0"
echo Starting ScanMeow API on http://0.0.0.0:8765
echo Emulator in app uses http://10.0.2.2:8765
echo.
python -m uvicorn api_server:app --host 0.0.0.0 --port 8765
pause
