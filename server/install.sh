#!/usr/bin/env bash
# Установка сервера бригады «Органайзера монтажника» на Ubuntu/Debian.
# Запуск (от root):
#   curl -fsSL https://github.com/Z0rD4n0v1ch/organizer-app/raw/brigade-sync/server/install.sh | bash -s -- brig.example.ru
# Без домена (только для проверки, без шифрования):
#   curl -fsSL https://github.com/Z0rD4n0v1ch/organizer-app/raw/brigade-sync/server/install.sh | bash
set -euo pipefail
DOMAIN="${1:-}"
PB_VER="0.40.6"
BRANCH="brigade-sync"
DIR=/opt/brigade
[ "$(id -u)" = 0 ] || { echo "Запустите от root (sudo -i)"; exit 1; }

echo "== 1/5 Пакеты"
apt-get update -qq && apt-get install -y -qq curl unzip ca-certificates >/dev/null

echo "== 2/5 PocketBase $PB_VER"
mkdir -p "$DIR"; cd "$DIR"
ARCH=amd64; [ "$(uname -m)" = aarch64 ] && ARCH=arm64
curl -fsSL -o pb.zip "https://github.com/pocketbase/pocketbase/releases/download/v${PB_VER}/pocketbase_${PB_VER}_linux_${ARCH}.zip"
unzip -o -q pb.zip pocketbase && rm pb.zip && chmod +x pocketbase

echo "== 3/5 Схема и API бригады"
curl -fsSL -o src.tgz "https://github.com/Z0rD4n0v1ch/organizer-app/archive/refs/heads/${BRANCH}.tar.gz"
rm -rf pb_migrations pb_hooks && tar -xzf src.tgz --strip-components=2 "organizer-app-${BRANCH}/server/pb_migrations" "organizer-app-${BRANCH}/server/pb_hooks" && rm src.tgz
id brigade >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin -d "$DIR" brigade
mkdir -p pb_data && chown -R brigade:brigade "$DIR"
sudo -u brigade ./pocketbase migrate up --dir="$DIR/pb_data" --migrationsDir="$DIR/pb_migrations" >/dev/null
ADMIN_PASS=$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 20)
sudo -u brigade ./pocketbase superuser upsert admin@brigade.local "$ADMIN_PASS" --dir="$DIR/pb_data" >/dev/null

echo "== 4/5 Служба"
if [ -n "$DOMAIN" ]; then ARGS="serve $DOMAIN"; URL="https://$DOMAIN"; else ARGS="serve --http=0.0.0.0:80"; URL="http://$(curl -fsS4 https://ifconfig.me 2>/dev/null || hostname -I | awk '{print $1}')"; fi
cat > /etc/systemd/system/brigade.service <<UNIT
[Unit]
Description=Сервер бригады (PocketBase)
After=network-online.target
Wants=network-online.target

[Service]
User=brigade
WorkingDirectory=$DIR
ExecStart=$DIR/pocketbase $ARGS --dir=$DIR/pb_data --migrationsDir=$DIR/pb_migrations --hooksDir=$DIR/pb_hooks
Restart=always
RestartSec=3
AmbientCapabilities=CAP_NET_BIND_SERVICE
LimitNOFILE=4096

[Install]
WantedBy=multi-user.target
UNIT
systemctl daemon-reload && systemctl enable --now brigade >/dev/null
if command -v ufw >/dev/null && ufw status | grep -q active; then ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null; fi

echo "== 5/5 Проверка"
sleep 4
if curl -fsS -o /dev/null -m 10 "http://127.0.0.1:80/api/health" 2>/dev/null || curl -fsSk -o /dev/null -m 10 "https://127.0.0.1/api/health" 2>/dev/null; then OK=1; else OK=0; fi
echo
echo "============================================================"
[ "$OK" = 1 ] && echo " Сервер бригады работает." || echo " Сервер запущен, но не ответил — пришлите вывод: journalctl -u brigade -n 50"
echo " Адрес для приложения:  $URL"
echo " Панель администратора: $URL/_/"
echo "   логин:  admin@brigade.local"
echo "   пароль: $ADMIN_PASS"
echo " Сохраните пароль — он показывается один раз. Никому его не отправляйте."
echo "============================================================"
