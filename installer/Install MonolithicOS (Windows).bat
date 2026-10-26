@echo off
title MonolithicOS installer
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0files\install.ps1" install
if errorlevel 1 pause
