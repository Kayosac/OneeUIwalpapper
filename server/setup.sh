#!/bin/sh
# Первый запуск на VPS: создаёт .env со случайными токенами и поднимает контейнер.
# Повторный запуск не меняет токены — только пересобирает и перезапускает.
set -eu
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  gen() { python3 -c 'import secrets; print(secrets.token_urlsafe(24))' 2>/dev/null \
          || head -c 18 /dev/urandom | base64 | tr '+/' '-_' | tr -d '='; }
  umask 077
  {
    echo "INFOWALL_WRITE_TOKEN=$(gen)"
    echo "INFOWALL_READ_TOKEN=$(gen)"
  } > .env
  echo "Создан .env с новыми токенами."
fi

docker compose up -d --build

echo
echo "Токены (сохрани, они нужны клиентам и обоям):"
sed 's/^/  /' .env
echo
echo "Проверка: curl -s http://127.0.0.1:8787/health"
