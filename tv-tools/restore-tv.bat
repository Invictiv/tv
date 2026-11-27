@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"
title Lite TV - вернуть как было
if exist "%~dp0platform-tools\adb.exe" set "PATH=%~dp0platform-tools;%PATH%"

adb version >nul 2>&1
if errorlevel 1 goto no_adb

echo.
echo Вернёт все отключённые приложения и настройки анимации.
echo.

:ask_ip
set "IP="
set /p "IP=IP-адрес телевизора: "
if "%IP%"=="" goto ask_ip
echo %IP%| findstr /c:":" >nul
if errorlevel 1 set "IP=%IP%:5555"

adb connect %IP%
echo Жду подключения... (на ТВ может появиться запрос - нажмите OK)
set /a TRIES=0

:wait_dev
set "STATE="
for /f "delims=" %%s in ('adb -s %IP% get-state 2^>nul') do set "STATE=%%s"
if "%STATE%"=="device" goto connected
set /a TRIES+=1
if %TRIES% GEQ 45 goto fail_connect
set /a MOD=TRIES %% 5
if %MOD%==0 adb connect %IP% >nul 2>&1
timeout /t 1 /nobreak >nul
goto wait_dev

:connected
adb -s %IP% shell mkdir -p /data/local/tmp/litetv
adb -s %IP% push "tv\litetv.sh" /data/local/tmp/litetv/litetv.sh >nul
adb -s %IP% shell ls /data/local/tmp/litetv/changed.txt >nul 2>&1
if errorlevel 1 if exist "backup\changed.txt" adb -s %IP% push "backup\changed.txt" /data/local/tmp/litetv/changed.txt >nul
adb -s %IP% shell sh /data/local/tmp/litetv/litetv.sh restore

echo.
set "U=0"
set /p "U=Удалить и сам лаунчер Lite TV? 1 - да, 0 - нет [0]: "
if "%U%"=="1" adb -s %IP% uninstall io.lite.tvlauncher

echo.
set "R=1"
set /p "R=Перезагрузить телевизор сейчас? 1 - да, 0 - нет [1]: "
if "%R%"=="1" adb -s %IP% reboot
echo Готово.
pause
exit /b 0

:no_adb
echo Не найден adb. Распакуйте platform-tools рядом с этим файлом:
echo   https://developer.android.com/tools/releases/platform-tools
pause
exit /b 1

:fail_connect
echo Не удалось подключиться к %IP%.
pause
exit /b 1
