#!/usr/bin/env bash
# Lite TV: вернуть телевизор как было (macOS / Linux).
cd "$(dirname "$0")" || exit 1
[ -x ./platform-tools/adb ] && export PATH="$PWD/platform-tools:$PATH"
command -v adb >/dev/null 2>&1 || { echo "Не найден adb (см. README)"; exit 1; }

IP=""
while [ -z "$IP" ]; do read -rp "IP-адрес телевизора: " IP; done
[[ "$IP" == *:* ]] || IP="$IP:5555"

adb connect "$IP"
echo "Жду подключения... (на ТВ может появиться запрос — нажмите OK)"
STATE=""
for i in $(seq 1 45); do
    STATE=$(adb -s "$IP" get-state 2>/dev/null)
    [ "$STATE" = device ] && break
    [ $((i % 5)) -eq 0 ] && adb connect "$IP" >/dev/null 2>&1
    sleep 1
done
[ "$STATE" = device ] || { echo "Не удалось подключиться к $IP."; exit 1; }

A=(adb -s "$IP")
"${A[@]}" shell mkdir -p /data/local/tmp/litetv
"${A[@]}" push tv/litetv.sh /data/local/tmp/litetv/litetv.sh >/dev/null
if ! "${A[@]}" shell ls /data/local/tmp/litetv/changed.txt >/dev/null 2>&1 && [ -f backup/changed.txt ]; then
    "${A[@]}" push backup/changed.txt /data/local/tmp/litetv/changed.txt >/dev/null
fi
"${A[@]}" shell sh /data/local/tmp/litetv/litetv.sh restore

echo
read -rp "Удалить и сам лаунчер Lite TV? 1 — да, 0 — нет [0]: " U
[ "$U" = 1 ] && "${A[@]}" uninstall io.lite.tvlauncher
read -rp "Перезагрузить телевизор сейчас? 1 — да, 0 — нет [1]: " R
[ "${R:-1}" = 1 ] && "${A[@]}" reboot
echo "Готово."
