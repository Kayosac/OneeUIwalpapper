# InfoWall — живые обои с мониторингом для планшета (One UI 8.5)

Обои рисуют часы, дату, состояние планшета (батарея, память, место, сеть)
и карточки из твоего JSON-источника. Работают вместе со стандартным
One UI Home, поэтому таскбар, жесты и Edge-панель остаются.

## Шаг 1. Плотная сетка иконок
1. Galaxy Store → установить **Good Lock**.
2. В Good Lock найти модуль **Home Up** → установить.
3. Home Up → включить → раздел главного экрана → настройка сетки / **DIY Home**.
4. Задать сетку (например 8×6 или плотнее) отдельно для портретной и альбомной ориентации.

## Шаг 2. Установить на планшет (без компьютера)
APK собирает GitHub Actions на каждый push в `main`.

1. На планшете открыть:
   **https://github.com/Kayosac/oneeuiwalpapper/releases/latest/download/InfoWall.apk**
2. Разрешить браузеру установку неизвестных приложений (Android спросит сам) → Установить.
3. Открыть InfoWall → заполнить настройки → **«Сохранить и установить обои»** → «Главный экран».

Статус сборки — вкладка **Actions** в репозитории.

### Постоянная подпись (чтобы обновления вставали поверх)
Без неё каждая сборка подписана новым debug-ключом, и перед установкой новой версии
старую придётся удалять. Один раз:

1. Repo → Settings → Secrets and variables → Actions → New repository secret.
2. Имя `KEYSTORE_B64`, значение — keystore в base64
   (`base64 -i infowall.keystore` на Mac, либо файл, который прислал Claude).
3. Если у ключа пароль не `infowall` — ещё секрет `KEY_PASS`.
4. Actions → Build APK → Run workflow.

Ключ в репозиторий не коммитится (`*.keystore` в .gitignore).

## Шаг 3. Менять код с планшета
- Попросить Claude — он правит и пушит, через пару минут по той же ссылке новая версия.
- Или самому: в браузере на github.com открыть репозиторий и нажать `.` — откроется веб-редактор,
  коммит из него тоже запускает сборку.

## Шаг 4. (Опционально) Сборка на Mac
Android Studio → Open → эта папка → ▶ Run, либо `./gradlew installDebug`.

## Шаг 5. Источник данных (необязательно)
Любой HTTP(S)-адрес, который отдаёт JSON. Поддерживаются три формата:

```json
{ "title": "Хаб", "items": [ { "label": "CPU", "value": "42%", "state": "ok" } ] }
```
```json
[ { "label": "CPU", "value": "42%", "state": "warn" } ]
```
```json
{ "CPU": "42%", "Диск": "71%" }
```

`state`: `ok` / `warn` / `bad` → зелёная / жёлтая / красная точка; без state — без точки.

Карточка-лимит: добавь `used_percent` (0–100, сколько израсходовано) и `resets_at`
(Unix-секунды). Обои покажут остаток, полоску, «через 2ч 10м» и сами обнулят
расход, когда время сброса наступит. `updated_at` — когда данные получены:
если старше 15 минут, в углу карточки видно время.
Можно указать токен — уйдёт заголовком `Authorization: Bearer <токен>`.
Обычный `http://` в локальной сети разрешён.

Быстрый тест с Mac (планшет в той же Wi-Fi):
```bash
cd example
python3 -m http.server 8080
# URL в InfoWall: http://<IP-мака>:8080/status.json
```
Кнопка «Проверить источник» показывает, что именно распарсилось.

## Как это экономит батарею
- Рисует только когда обои видны; перерисовка раз в 30 секунд (по смене минуты).
- Сеть дёргается не чаще заданного интервала (мин. 15 с, по умолчанию 300 с),
  при ошибке — повтор через минуту.
- Никаких фоновых сервисов: когда экран выключен, приложение ничего не делает.

## Файлы
- `InfoWallpaperService.kt` — движок обоев, расписание, загрузка
- `Renderer.kt` — отрисовка (часы, карточки)
- `SystemStats.kt` — локальные метрики
- `RemoteSource.kt` — загрузка и разбор JSON
- `SettingsActivity.kt` — экран настроек

## Лимиты Claude и Codex на обоях

Схема: **клиенты отправляют → VPS хранит → обои забирают.**
Никаких токенов аккаунтов и неофициальных API: Claude Code сам отдаёт `rate_limits`
в свою статус-строку, Codex пишет их в локальный лог сессии. Скрипт отправляет на VPS
только проценты и время сброса.

```
Claude Code ─statusLine─┐
                        ├─ clients/push.py ── POST /push ──> VPS: server/ ── GET /limits.json ──> InfoWall
Codex ─────notify───────┘
```

Цифры обновляются при каждом ответе в Claude Code / Codex. Между ними обои показывают
последние данные, идёт обратный отсчёт, а в момент сброса окно само становится 100%.
Лимит Claude общий с claude.ai, поэтому расход в приложении увидишь при следующем
ответе в Claude Code. Для ChatGPT видны лимиты Codex, а не обычного чата.

### 1. Сервер на VPS (один раз)
```bash
git clone https://github.com/Kayosac/OneeUIwalpapper.git infowall
cd infowall/server
./setup.sh          # создаст .env с двумя токенами и поднимет Docker-контейнер
```
Без Docker: `INFOWALL_WRITE_TOKEN=... INFOWALL_READ_TOKEN=... python3 limits_server.py`.

Сервис слушает `127.0.0.1:8787`. Наружу — через reverse proxy с HTTPS, например Caddy:
```
limits.example.com {
    reverse_proxy 127.0.0.1:8787
}
```
Без прокси замени порт в `docker-compose.yml` на `"8787:8787"` и ходи по `http://IP:8787`
(работает, но токены идут открытым текстом).

Два токена: **WRITE** — только для клиентов, **READ** — только для обоев.
Утечёт READ — увидят твои проценты; утечёт WRITE — смогут подменить цифры. Больше ничего.

### 2. Клиент там, где запускаешь Claude Code / Codex
```bash
curl -fsSL https://raw.githubusercontent.com/Kayosac/OneeUIwalpapper/main/clients/push.py -o /tmp/push.py
python3 /tmp/push.py install --url https://limits.example.com --token <WRITE_TOKEN>
```
`install` копирует скрипт в `~/.infowall/`, прописывает `statusLine` в `~/.claude/settings.json`
(твоя старая статус-строка сохраняется и показывается как раньше) и `notify` в `~/.codex/config.toml`
(если там уже есть свой notify — не трогает и подскажет, как совместить). Перед правкой делает
`.infowall-backup`. Убрать всё: `python3 ~/.infowall/push.py uninstall`.

Проверка без Claude/Codex: `python3 ~/.infowall/push.py test` (убрать: `test --clear`).

### 3. Обои
InfoWall → URL `https://limits.example.com/limits.json`, токен — **READ_TOKEN**,
интервал 60 сек → «Проверить источник» → «Сохранить».

