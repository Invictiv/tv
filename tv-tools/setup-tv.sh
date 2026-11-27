#!/usr/bin/env bash
# Lite TV: ускорение Android-телевизора (macOS / Linux).
cd "$(dirname "$0")" || exit 1
[ -x ./platform-tools/adb ] && export PATH="$PWD/platform-tools:$PATH"
if ! command -v adb >/dev/null 2>&1; then
    echo "Не найден adb. Установите platform-tools:"
    echo "  macOS:  brew install android-platform-tools"
    echo "  Linux:  sudo apt install adb   (или распакуйте platform-tools рядом со скриптом)"
    exit 1
fi

APK=$(ls ./*.apk 2>/dev/null | head -n 1)

echo
echo "============================================"
echo "  Lite TV: ускорение Android-телевизора"
echo "============================================"
echo "На ТВ должна быть включена «Отладка по сети»/«Отладка по USB» в «Параметрах разработчика»."
echo

IP=""
while [ -z "$IP" ]; do read -rp "IP-адрес телевизора, например 192.168.1.50: " IP; done
[[ "$IP" == *:* ]] || IP="$IP:5555"

adb connect "$IP"
echo "Если на ТВ появился запрос «Разрешить отладку?» — отметьте «Всегда разрешать» и нажмите OK."
echo "Жду подключения..."
STATE=""
for i in $(seq 1 45); do
    STATE=$(adb -s "$IP" get-state 2>/dev/null)
    [ "$STATE" = device ] && break
    [ $((i % 5)) -eq 0 ] && adb connect "$IP" >/dev/null 2>&1
    sleep 1
done
if [ "$STATE" != device ]; then
    echo "Не удалось подключиться к $IP. Проверьте сеть, IP и что отладка включена (см. README)."
    exit 1
fi

A=(adb -s "$IP")
echo
echo "Подключено: $("${A[@]}" shell getprop ro.product.model | tr -d '\r')"

mkdir -p backup
[ -f backup/packages-before.txt ] || "${A[@]}" shell pm list packages -f > backup/packages-before.txt
echo "Список всех приложений сохранён: backup/packages-before.txt"

LAUNCHER_OK=0
if [ -n "$APK" ]; then
    echo
    echo "Устанавливаю лаунчер Lite TV..."
    "${A[@]}" install -r "$APK"
    if "${A[@]}" shell pm path io.lite.tvlauncher | grep -q "package:"; then
        LAUNCHER_OK=1
        "${A[@]}" shell cmd package compile -m speed -f io.lite.tvlauncher >/dev/null 2>&1
        "${A[@]}" shell cmd package set-home-activity io.lite.tvlauncher/.MainActivity >/dev/null 2>&1
        "${A[@]}" shell pm set-home-activity io.lite.tvlauncher/.MainActivity >/dev/null 2>&1
        "${A[@]}" shell cmd role add-role-holder android.app.role.HOME io.lite.tvlauncher >/dev/null 2>&1
        echo "Lite TV установлен и назначен главным экраном."
    else
        echo "Лаунчер не установился — стандартный главный экран оставляю."
    fi
else
    echo
    echo "Файл .apk рядом со скриптом не найден — стандартный главный экран оставляю."
fi

echo
echo "Что отключать:"
echo "  1 — безопасно: стандартный главный экран, рекомендации, подсказки, Play Игры и т.п. [рекомендую]"
echo "  2 — максимум: плюс Chromecast, голосовой поиск Google, заставки, Netflix"
read -rp "Ваш выбор [1]: " M
MODE=safe; [ "$M" = 2 ] && MODE=full

"${A[@]}" shell mkdir -p /data/local/tmp/litetv
"${A[@]}" push tv/litetv.sh /data/local/tmp/litetv/litetv.sh >/dev/null
"${A[@]}" push tv/bloat-list.txt /data/local/tmp/litetv/bloat-list.txt >/dev/null
"${A[@]}" shell sh /data/local/tmp/litetv/litetv.sh setup "$MODE" "$LAUNCHER_OK"
"${A[@]}" pull /data/local/tmp/litetv/changed.txt backup/changed.txt >/dev/null 2>&1

echo
read -rp "Перезагрузить телевизор сейчас? 1 — да, 0 — нет [1]: " R
[ "${R:-1}" = 1 ] && "${A[@]}" reboot
echo "Готово. Вернуть всё как было: ./restore-tv.sh"
