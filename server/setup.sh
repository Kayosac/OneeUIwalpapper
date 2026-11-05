#!/usr/bin/env bash
# Установка InfoWall limits на VPS одной командой:
#   curl -fsSL https://raw.githubusercontent.com/Kayosac/OneeUIwalpapper/main/server/setup.sh | bash
# Можно запускать повторно: токены сохраняются, сервис обновляется.
set -euo pipefail

REPO="https://github.com/Kayosac/OneeUIwalpapper.git"
RAW="https://raw.githubusercontent.com/Kayosac/OneeUIwalpapper/main"
DIR="$HOME/infowall"
PORT=8787

SUDO=""
[ "$(id -u)" -ne 0 ] && SUDO="sudo"
say() { printf '\n\033[1m%s\033[0m\n' "$*"; }

say "1/4 Проверяю Docker и git…"
if ! command -v docker >/dev/null 2>&1; then
  curl -fsSL https://get.docker.com | $SUDO sh
fi
if ! $SUDO docker compose version >/dev/null 2>&1; then
  $SUDO apt-get update -qq && $SUDO apt-get install -y -qq docker-compose-plugin
fi
if ! command -v git >/dev/null 2>&1; then
  $SUDO apt-get update -qq && $SUDO apt-get install -y -qq git
fi

say "2/4 Скачиваю InfoWall…"
if [ -d "$DIR/.git" ]; then
  git -C "$DIR" pull -q --ff-only
else
  git clone -q "$REPO" "$DIR"
fi
cd "$DIR/server"

say "3/4 Токены…"
gen() {
  python3 -c 'import secrets; print(secrets.token_urlsafe(24))' 2>/dev/null \
    || openssl rand -base64 24 2>/dev/null | tr '+/' '-_' | tr -d '=' \
    || head -c 18 /dev/urandom | base64 | tr '+/' '-_' | tr -d '='
}
if [ ! -f .env ]; then
  ( umask 077
    { echo "INFOWALL_WRITE_TOKEN=$(gen)"; echo "INFOWALL_READ_TOKEN=$(gen)"; } > .env )
fi
grep -q '^INFOWALL_BIND=' .env || echo "INFOWALL_BIND=0.0.0.0" >> .env
WRITE=$(grep '^INFOWALL_WRITE_TOKEN=' .env | cut -d= -f2-)
READ=$(grep '^INFOWALL_READ_TOKEN=' .env | cut -d= -f2-)

say "4/4 Запускаю…"
$SUDO docker compose up -d --build >/dev/null
if command -v ufw >/dev/null 2>&1 && $SUDO ufw status 2>/dev/null | grep -q "Status: active"; then
  $SUDO ufw allow "$PORT"/tcp >/dev/null
fi
ok=""
for _ in $(seq 1 20); do
  if curl -fsS "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then ok=1; break; fi
  sleep 1
done
[ -n "$ok" ] || { echo "Сервис не ответил. Лог: $SUDO docker logs infowall-limits"; exit 1; }

IP=$(curl -fsS --max-time 5 https://api.ipify.org 2>/dev/null || hostname -I | awk '{print $1}')
URL="http://$IP:$PORT"

cat <<EOF

==================== ГОТОВО ====================

► НА МАКЕ: открой «Терминал», вставь и нажми Enter:

curl -fsSL $RAW/clients/push.py -o /tmp/push.py && python3 /tmp/push.py install --url $URL --token $WRITE

► НА ПЛАНШЕТЕ: InfoWall → вставь:
   URL:       $URL/limits.json
   Токен:     $READ
   Интервал:  60
   → «Проверить источник» → «Сохранить»

Показать это снова:  bash $DIR/server/setup.sh
=================================================
EOF
