#!/usr/bin/env bash
# Установка агента InfoWall на сервер (Ubuntu / Debian) одной командой:
#   curl -fsSL https://raw.githubusercontent.com/Kayosac/OneeUIwalpapper/main/agent/install.sh | sudo bash
# Свой порт:            ... | sudo PORT=9100 bash
# Своё имя хостинга:    ... | sudo NAME=AEZA bash
# Новые токен и сертификат: ... | sudo RESET=1 bash
# Повторный запуск безопасен: токен, сертификат и порт сохраняются, агент обновляется.
set -euo pipefail

RAW="https://raw.githubusercontent.com/Kayosac/OneeUIwalpapper/main/agent"
APP=/opt/infowall-agent
ETC=/etc/infowall-agent
USR=infowall-agent
UNIT=/etc/systemd/system/infowall-agent.service

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
[ "$(id -u)" -eq 0 ] || { echo "Запусти через sudo"; exit 1; }

say "1/5 Зависимости…"
need=()
command -v python3 >/dev/null 2>&1 || need+=(python3)
command -v openssl >/dev/null 2>&1 || need+=(openssl)
command -v ss >/dev/null 2>&1 || need+=(iproute2)
command -v curl >/dev/null 2>&1 || need+=(curl)
if [ ${#need[@]} -gt 0 ]; then
  apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${need[@]}"
fi

say "2/5 Пользователь и файлы…"
id -u "$USR" >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin "$USR"
mkdir -p "$APP" "$ETC"
curl -fsSL "$RAW/infowall_agent.py" -o "$APP/infowall_agent.py"
chmod 755 "$APP/infowall_agent.py"

say "3/5 Порт, токен, сертификат…"
port_free() { [ -z "$(ss -Hltn "sport = :$1" 2>/dev/null)" ]; }
OLD_PORT=""
[ -f "$ETC/agent.json" ] && OLD_PORT=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("port",""))' "$ETC/agent.json" 2>/dev/null || true)
if [ -n "${PORT:-}" ]; then
  P="$PORT"
  # если порт занят не нашим агентом — стоп
  if ! port_free "$P" && [ "$P" != "$OLD_PORT" ]; then echo "Порт $P занят. Выбери другой: PORT=..."; exit 1; fi
elif [ -n "$OLD_PORT" ] && [ -z "${RESET:-}" ]; then
  P="$OLD_PORT"
else
  P=""
  for cand in $(seq 8790 8899); do
    if port_free "$cand"; then P="$cand"; break; fi
  done
  [ -n "$P" ] || { echo "Не нашёл свободный порт в 8790–8899. Укажи свой: PORT=..."; exit 1; }
fi

if [ ! -f "$ETC/token" ] || [ -n "${RESET:-}" ]; then
  python3 -c 'import secrets;print(secrets.token_urlsafe(32))' > "$ETC/token"
fi
if [ ! -f "$ETC/cert.pem" ] || [ -n "${RESET:-}" ]; then
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
    -subj "/CN=infowall-agent" -keyout "$ETC/key.pem" -out "$ETC/cert.pem" >/dev/null 2>&1
fi
TOKEN=$(cat "$ETC/token")
python3 - "$ETC" "$P" "$TOKEN" <<'PY'
import json, sys
etc, port, token = sys.argv[1], int(sys.argv[2]), sys.argv[3]
json.dump({"port": port, "token": token, "cert": etc + "/cert.pem", "key": etc + "/key.pem"},
          open(etc + "/agent.json", "w"))
PY
chown -R "$USR:$USR" "$ETC"
chmod 700 "$ETC"; chmod 600 "$ETC"/*

say "4/5 Служба…"
cat > "$UNIT" <<EOF
[Unit]
Description=InfoWall agent (read-only server metrics)
After=network-online.target
Wants=network-online.target

[Service]
User=$USR
Group=$USR
ExecStart=/usr/bin/python3 $APP/infowall_agent.py
Restart=always
RestartSec=5
# ограничения: только чтение системы, без привилегий, без доступа к домашним папкам
NoNewPrivileges=yes
CapabilityBoundingSet=
AmbientCapabilities=
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
ProtectClock=yes
ProtectHostname=yes
RestrictNamespaces=yes
RestrictRealtime=yes
RestrictSUIDSGID=yes
LockPersonality=yes
MemoryDenyWriteExecute=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
ReadOnlyPaths=$ETC
MemoryMax=48M
CPUQuota=10%
TasksMax=32

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now infowall-agent >/dev/null 2>&1
systemctl restart infowall-agent

if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
  ufw allow "$P"/tcp >/dev/null
fi

say "5/5 Проверка…"
ok=""
for _ in $(seq 1 15); do
  sleep 1
  code=$(curl -sk -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $TOKEN" "https://127.0.0.1:$P/metrics" || true)
  if [ "$code" = "200" ]; then ok=1; break; fi
done
[ -n "$ok" ] || { echo "Агент не ответил. Лог: journalctl -u infowall-agent -n 50"; exit 1; }

IP=$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')
FP=$(openssl x509 -in "$ETC/cert.pem" -noout -fingerprint -sha256 | cut -d= -f2 | tr -d ':' | tr 'A-F' 'a-f')
# название хостинга: по владельцу IP-адреса («AS24940 Hetzner Online GmbH» → HETZNER); своё — NAME=...
if [ -z "${NAME:-}" ]; then
  ORG=$(curl -fsS --max-time 5 "https://ipinfo.io/$IP/org" 2>/dev/null || true)
  NAME=$(printf '%s' "$ORG" | sed -E 's/^AS[0-9]+ +//' | awk '{print $1}' | tr -d ',.')
  [ -n "$NAME" ] || NAME=$(hostname -s)
fi
NAME=$(printf '%s' "$NAME" | tr -cd 'A-Za-z0-9._-' | tr 'a-z' 'A-Z' | cut -c1-12)

cat <<EOF

==================== ГОТОВО ====================
Агент работает на порту $P.

► Скопируй эту строку в InfoWall → «Серверы»:

iwa1|$NAME|https://$IP:$P|$TOKEN|$FP

Если у хостинга есть свой файрвол в панели управления —
открой там входящий TCP-порт $P.
Удалить агента: systemctl disable --now infowall-agent && rm -rf $APP $ETC $UNIT
=================================================
EOF
