#!/usr/bin/env python3
"""
InfoWall limits server — точка сбора лимитов ИИ на VPS.

Клиенты (clients/push.py рядом с Claude Code / Codex) присылают свои
rate_limits, сервер хранит последнее значение и отдаёт его обоям
в формате InfoWall.

  POST /push            Authorization: Bearer $INFOWALL_WRITE_TOKEN
                        (пустой windows — удалить источник)
  GET  /limits.json     Authorization: Bearer $INFOWALL_READ_TOKEN
  GET  /health          без авторизации

Только стандартная библиотека Python 3.9+.

Переменные окружения:
  INFOWALL_WRITE_TOKEN   обязателен
  INFOWALL_READ_TOKEN    обязателен
  INFOWALL_HOST          по умолчанию 0.0.0.0
  INFOWALL_PORT          по умолчанию 8787
  INFOWALL_DATA          каталог для state.json, по умолчанию ./data
"""

import hmac
import json
import os
import re
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 64 * 1024
MAX_SOURCES = 16
MAX_WINDOWS = 8
SOURCE_RE = re.compile(r"^[a-z0-9_-]{1,32}$")

WRITE_TOKEN = os.environ.get("INFOWALL_WRITE_TOKEN", "")
READ_TOKEN = os.environ.get("INFOWALL_READ_TOKEN", "")
HOST = os.environ.get("INFOWALL_HOST", "0.0.0.0")
PORT = int(os.environ.get("INFOWALL_PORT", "8787"))
DATA_DIR = os.environ.get("INFOWALL_DATA", os.path.join(os.path.dirname(os.path.abspath(__file__)), "data"))
STATE_FILE = os.path.join(DATA_DIR, "state.json")

_lock = threading.Lock()
_state = {"sources": {}}


# ---------- хранение ----------

def load_state():
    global _state
    try:
        with open(STATE_FILE, "r", encoding="utf-8") as f:
            data = json.load(f)
        if isinstance(data, dict) and isinstance(data.get("sources"), dict):
            _state = data
    except FileNotFoundError:
        pass
    except Exception as e:  # битый файл не должен ронять сервис
        print(f"warn: не смог прочитать {STATE_FILE}: {e}", file=sys.stderr)


def save_state():
    os.makedirs(DATA_DIR, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=DATA_DIR, prefix=".state-", suffix=".json")
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        json.dump(_state, f, ensure_ascii=False)
    os.replace(tmp, STATE_FILE)


# ---------- валидация входа ----------

def _num(v, lo=None, hi=None):
    if isinstance(v, bool) or not isinstance(v, (int, float)):
        return None
    v = float(v)
    if v != v:  # NaN
        return None
    if lo is not None:
        v = max(lo, v)
    if hi is not None:
        v = min(hi, v)
    return v


def _text(v, limit):
    if not isinstance(v, str):
        return None
    v = v.strip()
    return v[:limit] if v else None


def clean_push(body):
    """Возвращает (source, record) или бросает ValueError."""
    if not isinstance(body, dict):
        raise ValueError("ожидался JSON-объект")
    source = body.get("source")
    if not isinstance(source, str) or not SOURCE_RE.match(source):
        raise ValueError("source: a-z, 0-9, _ или -, до 32 символов")
    windows_in = body.get("windows")
    if not isinstance(windows_in, list):
        raise ValueError("windows: ожидался массив")

    windows = []
    for w in windows_in[:MAX_WINDOWS]:
        if not isinstance(w, dict):
            continue
        used = _num(w.get("used_percent"), 0.0, 100.0)
        if used is None:
            continue
        resets = _num(w.get("resets_at"), 0)
        windows.append({
            "id": _text(w.get("id"), 32) or f"w{len(windows)}",
            "label": _text(w.get("label"), 24) or "",
            "used_percent": round(used, 1),
            "resets_at": int(resets) if resets else None,
        })

    record = {
        "label": _text(body.get("label"), 24) or source.capitalize(),
        "windows": windows,
        "updated_at": int(time.time()),
    }
    return source, record


# ---------- выдача для обоев ----------

def remaining_state(remaining):
    if remaining < 10:
        return "bad"
    if remaining < 30:
        return "warn"
    return "ok"


def render(now=None):
    now = int(now or time.time())
    items = []
    with _lock:
        sources = sorted(_state["sources"].items(), key=lambda kv: kv[1].get("label", kv[0]))
        for _, rec in sources:
            for w in rec.get("windows", []):
                used = w["used_percent"]
                resets = w.get("resets_at")
                if resets and now >= resets:
                    # окно уже сбросилось — новых данных ещё не было, но мы знаем, что там 0
                    used, resets = 0.0, None
                remaining = 100.0 - used
                label = rec["label"] + (" · " + w["label"] if w.get("label") else "")
                items.append({
                    "label": label,
                    "value": f"{remaining:.0f}%",
                    "used_percent": used,
                    "resets_at": resets,
                    "updated_at": rec.get("updated_at"),
                    "state": remaining_state(remaining),
                })
    return {"title": "Лимиты ИИ", "items": items, "generated_at": now}


# ---------- HTTP ----------

def _authorized(header, token):
    if not header or not header.startswith("Bearer "):
        return False
    return hmac.compare_digest(header[7:].strip().encode(), token.encode())


class Handler(BaseHTTPRequestHandler):
    server_version = "InfoWallLimits/1"

    def log_message(self, fmt, *args):  # короткий лог без тела запросов
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def _send(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/health":
            return self._send(200, {"ok": True})
        if path in ("/limits.json", "/limits"):
            if not _authorized(self.headers.get("Authorization"), READ_TOKEN):
                return self._send(401, {"error": "unauthorized"})
            return self._send(200, render())
        self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path.split("?", 1)[0] != "/push":
            return self._send(404, {"error": "not found"})
        if not _authorized(self.headers.get("Authorization"), WRITE_TOKEN):
            return self._send(401, {"error": "unauthorized"})
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            length = -1
        if length <= 0 or length > MAX_BODY:
            return self._send(413 if length > MAX_BODY else 400, {"error": "bad body size"})
        try:
            body = json.loads(self.rfile.read(length).decode("utf-8"))
            source, record = clean_push(body)
        except (ValueError, UnicodeDecodeError) as e:
            return self._send(400, {"error": str(e)})

        with _lock:
            sources = _state["sources"]
            if not record["windows"]:
                # пустой список — источник убираем (так же работает проверка связи)
                existed = sources.pop(source, None) is not None
                if existed:
                    try:
                        save_state()
                    except OSError as e:
                        print(f"warn: не смог сохранить состояние: {e}", file=sys.stderr)
                return self._send(200, {"ok": True, "removed": existed})
            if source not in sources and len(sources) >= MAX_SOURCES:
                return self._send(409, {"error": "слишком много источников"})
            sources[source] = record
            try:
                save_state()
            except OSError as e:
                print(f"warn: не смог сохранить состояние: {e}", file=sys.stderr)
        self._send(200, {"ok": True, "windows": len(record["windows"])})


def main():
    if len(WRITE_TOKEN) < 16 or len(READ_TOKEN) < 16:
        sys.exit("Нужны INFOWALL_WRITE_TOKEN и INFOWALL_READ_TOKEN (каждый от 16 символов).\n"
                 "Сгенерировать: python3 -c 'import secrets; print(secrets.token_urlsafe(24))'")
    if WRITE_TOKEN == READ_TOKEN:
        sys.exit("Токены записи и чтения должны отличаться.")
    load_state()
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"InfoWall limits server: http://{HOST}:{PORT}  (data: {DATA_DIR})", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
