#!/system/bin/sh
# Выполняется НА ТЕЛЕВИЗОРЕ (через adb shell). Сам по себе ничего не скачивает.
#   sh litetv.sh setup <safe|full> <launcher_ok:1|0>
#   sh litetv.sh restore
# Всё, что меняется, пишется в журнал changed.txt — по нему restore возвращает как было.

set -f
DIR=/data/local/tmp/litetv
LIST="$DIR/bloat-list.txt"
LOG="$DIR/changed.txt"
BAK="$DIR/settings-backup.txt"
NL='
'
CR=$(printf '\r')

setup() {
    MODE="${1:-safe}"
    LAUNCHER_OK="${2:-0}"
    touch "$LOG"
    installed="$NL$(pm list packages -e 2>/dev/null)$NL"

    echo ""
    echo "== Отключаю лишнее (режим: $MODE) =="
    section=""
    count=0
    while IFS= read -r line || [ -n "$line" ]; do
        line="${line%%#*}"
        line="${line%$CR}"
        set -- $line
        [ $# -eq 0 ] && continue
        pkg="$1"
        case "$pkg" in
            "[launcher]") section=launcher; continue ;;
            "[safe]") section=safe; continue ;;
            "[full]") section=full; continue ;;
        esac
        case "$section" in
            launcher) [ "$LAUNCHER_OK" = 1 ] || continue ;;
            full) [ "$MODE" = full ] || continue ;;
        esac
        case "$installed" in
            *"${NL}package:${pkg}${NL}"*) ;;
            *) continue ;;
        esac

        out=$(pm disable-user --user 0 "$pkg" 2>&1)
        case "$out" in
            *"new state: disabled"*)
                echo "D $pkg" >> "$LOG"
                echo "  [откл]    $pkg"
                count=$((count + 1))
                ;;
            *)
                # Некоторые прошивки запрещают disable — тогда «удаляем для пользователя» (тоже обратимо).
                out2=$(pm uninstall -k --user 0 "$pkg" 2>&1)
                case "$out2" in
                    *Success*)
                        echo "U $pkg" >> "$LOG"
                        echo "  [скрыт]   $pkg"
                        count=$((count + 1))
                        ;;
                    *) echo "  [пропуск] $pkg — прошивка не разрешает" ;;
                esac
                ;;
        esac
    done < "$LIST"
    echo "  Отключено пакетов: $count"

    echo ""
    echo "== Ускоряю систему =="
    if [ ! -f "$BAK" ]; then
        for k in window_animation_scale transition_animation_scale animator_duration_scale; do
            echo "global $k $(settings get global $k)" >> "$BAK"
        done
    fi
    settings put global window_animation_scale 0.5
    settings put global transition_animation_scale 0.5
    settings put global animator_duration_scale 0.5
    echo "  Анимации системы: 0.5x (быстрее, но не дёргано)"
    pm trim-caches 999G >/dev/null 2>&1 && echo "  Кеш приложений очищен"
}

restore() {
    echo ""
    echo "== Возвращаю как было =="
    if [ -f "$LOG" ]; then
        while read -r act pkg; do
            pkg="${pkg%$CR}"
            [ -z "$pkg" ] && continue
            case "$act" in
                D) pm enable --user 0 "$pkg" >/dev/null 2>&1 && echo "  [вкл]     $pkg" ;;
                U) { cmd package install-existing "$pkg" || pm install-existing "$pkg"; } >/dev/null 2>&1 \
                       && echo "  [вернул]  $pkg" ;;
            esac
        done < "$LOG"
        mv "$LOG" "$LOG.restored"
    else
        echo "  Журнал изменений не найден — пакеты не трогаю"
    fi

    if [ -f "$BAK" ]; then
        while read -r ns key val; do
            val="${val%$CR}"
            if [ -z "$val" ] || [ "$val" = null ]; then
                settings delete "$ns" "$key" >/dev/null 2>&1
            else
                settings put "$ns" "$key" "$val"
            fi
        done < "$BAK"
        mv "$BAK" "$BAK.restored"
        echo "  Анимации возвращены"
    fi
}

case "$1" in
    setup) shift; setup "$@" ;;
    restore) restore ;;
    *) echo "usage: litetv.sh setup <safe|full> <0|1> | restore" ;;
esac
