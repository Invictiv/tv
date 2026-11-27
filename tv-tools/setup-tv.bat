@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"
title Lite TV - ускорение телевизора
if exist "%~dp0platform-tools\adb.exe" set "PATH=%~dp0platform-tools;%PATH%"

adb version >nul 2>&1
if errorlevel 1 goto no_adb

set "APK="
for %%f in ("%~dp0*.apk") do set "APK=%%f"

echo.
echo ============================================
echo   Lite TV: ускорение Android-телевизора
echo ============================================
echo.
echo На телевизоре должна быть включена «Отладка по сети» или «Отладка по USB»
echo в «Параметрах разработчика». IP-адрес смотрите в Настройки - Сеть.
echo.

:ask_ip
set "IP="
set /p "IP=IP-адрес телевизора, например 192.168.1.50: "
if "%IP%"=="" goto ask_ip
echo %IP%| findstr /c:":" >nul
if errorlevel 1 set "IP=%IP%:5555"

adb connect %IP%
echo.
echo Если на экране ТВ появился запрос «Разрешить отладку?» -
echo отметьте «Всегда разрешать с этого компьютера» и нажмите OK.
echo Жду подключения...
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
set "MODEL="
for /f "delims=" %%m in ('adb -s %IP% shell getprop ro.product.model') do set "MODEL=%%m"
echo.
echo Подключено: %MODEL%

if not exist backup mkdir backup
if not exist "backup\packages-before.txt" adb -s %IP% shell pm list packages -f > "backup\packages-before.txt"
echo Список всех приложений сохранён: backup\packages-before.txt

set "LAUNCHER_OK=0"
if not defined APK goto no_apk
echo.
echo Устанавливаю лаунчер Lite TV...
adb -s %IP% install -r "%APK%"
adb -s %IP% shell pm path io.lite.tvlauncher | findstr /c:"package:" >nul
if errorlevel 1 goto apk_failed
set "LAUNCHER_OK=1"
adb -s %IP% shell cmd package compile -m speed -f io.lite.tvlauncher >nul 2>&1
adb -s %IP% shell cmd package set-home-activity io.lite.tvlauncher/.MainActivity >nul 2>&1
adb -s %IP% shell pm set-home-activity io.lite.tvlauncher/.MainActivity >nul 2>&1
adb -s %IP% shell cmd role add-role-holder android.app.role.HOME io.lite.tvlauncher >nul 2>&1
echo Lite TV установлен и назначен главным экраном.
goto choose_mode

:apk_failed
echo Лаунчер не установился - стандартный главный экран оставляю.
goto choose_mode

:no_apk
echo.
echo Файл .apk рядом со скриптом не найден - стандартный главный экран оставляю.

:choose_mode
echo.
echo Что отключать:
echo   1 - безопасно: стандартный главный экран, рекомендации, подсказки, Play Игры и т.п. [рекомендую]
echo   2 - максимум: плюс Chromecast, голосовой поиск Google, заставки, Netflix
set "M=1"
set /p "M=Ваш выбор [1]: "
set "MODE=safe"
if "%M%"=="2" set "MODE=full"

adb -s %IP% shell mkdir -p /data/local/tmp/litetv
adb -s %IP% push "tv\litetv.sh" /data/local/tmp/litetv/litetv.sh >nul
adb -s %IP% push "tv\bloat-list.txt" /data/local/tmp/litetv/bloat-list.txt >nul
adb -s %IP% shell sh /data/local/tmp/litetv/litetv.sh setup %MODE% %LAUNCHER_OK%
adb -s %IP% pull /data/local/tmp/litetv/changed.txt "backup\changed.txt" >nul 2>&1

echo.
set "R=1"
set /p "R=Перезагрузить телевизор сейчас? 1 - да, 0 - нет [1]: "
if "%R%"=="1" adb -s %IP% reboot
echo.
echo Готово. Вернуть всё как было: restore-tv.bat
pause
exit /b 0

:no_adb
echo.
echo Не найден adb. Скачайте «SDK Platform-Tools for Windows»:
echo   https://developer.android.com/tools/releases/platform-tools
echo и распакуйте папку platform-tools рядом с этим файлом.
pause
exit /b 1

:fail_connect
echo.
echo Не удалось подключиться к %IP%.
echo Проверьте: ТВ и компьютер в одной сети, отладка включена, IP верный.
echo На Google TV с Android 11+ может понадобиться «Беспроводная отладка»: см. README.
pause
exit /b 1
