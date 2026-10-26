@echo off
title MonolithicOS uninstaller
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0files\install.ps1" uninstall
if errorlevel 1 pause
