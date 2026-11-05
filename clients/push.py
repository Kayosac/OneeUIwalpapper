#!/usr/bin/env python3
"""
InfoWall push — отправляет лимиты Claude Code и Codex на limits-сервер.

Никаких токенов аккаунтов: только данные, которые сами CLI выдают локально.
  * Claude Code — поле rate_limits из JSON, который он передаёт в statusline.
  * Codex       — последнее событие token_count.rate_limits из ~/.codex/sessions.

Команды:
  push.py install [--url URL --token WRITE_TOKEN] установить себя, хуки и фоновый опрос
                                                  (без аргументов — берёт сохранённые)
  push.py claude [--chain CMD]                    statusLine для Claude Code
  push.py codex [-- CMD ...]                      notify для Codex
  push.py test [--clear]                          тестовые карточки (или убрать их)
  push.py doctor                                  проверить всю цепочку
  push.py scan                                    разово прочитать лог Codex и отправить
  push.py uninstall                               убрать хуки

Только стандартная библиотека Python 3.8+.
"""

import calendar
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

HOME = os.path.expanduser("~")
BASE = os.path.join(HOME, ".infowall")
CONFIG = os.path.join(BASE, "config.json")
INSTALLED = os.path.join(BASE, "push.py")
CLAUDE_SETTINGS = os.path.join(HOME, ".claude", "settings.json")
CODEX_CONFIG = os.path.join(HOME, ".codex", "config.toml")
CODEX_SESSIONS = os.path.join(HOME, ".codex", "sessions")

THROTTLE_SEC = 60          # не чаще раза в минуту, если цифры не изменились
TIMEOUT_SEC = 5
MARK = "infowall"          # по этой метке находим свои хуки
PY = sys.executable or "python3"  # тот же Python, которым запускали install


# ---------------- конфиг и отправка ----------------

def load_config():
    url = os.environ.get("INFOWALL_URL")
    token = os.environ.get("INFOWALL_TOKEN")
    if not (url and token):
        try:
            with open(CONFIG, encoding="utf-8") as f:
                c = json.load(f)
            url = url or c.get("url")
            token = token or c.get("token")
        except (OSError, ValueError):
            pass
    return (url or "").rstrip("/"), token or ""


def post(payload):
    url, token = load_config()
    if not url or not token:
        raise RuntimeError("нет url/token: запусти push.py install")
    req = urllib.request.Request(
        url + "/push",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + token},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=TIMEOUT_SEC) as r:
        return r.status


def should_send(source, payload):
    """Шлём, если цифры поменялись или прошло THROTTLE_SEC."""
    path = os.path.join(BASE, f".last-{source}.json")
    key = json.dumps(payload["windows"], sort_keys=True)
    now = time.time()
    try:
        with open(path, encoding="utf-8") as f:
            last = json.load(f)
        if last.get("key") == key and now - last.get("at", 0) < THROTTLE_SEC:
            return False
    except (OSError, ValueError):
        pass
    try:
        os.makedirs(BASE, exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"key": key, "at": now}, f)
    except OSError:
        pass
    return True


def send_detached(payload, delay=0.0):
    """Отправка в отдельном процессе, чтобы не тормозить CLI."""
    # Codex зовёт хук раз за ответ, окна читаются позже — его не троттлим
    if payload["windows"] and not should_send(payload["source"], payload):
        return
    try:
        p = subprocess.Popen(
            [sys.executable, os.path.abspath(__file__), "_send", str(delay)],
            stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            start_new_session=True,
        )
        p.stdin.write(json.dumps(payload).encode("utf-8"))
        p.stdin.close()
    except OSError:
        pass


def cmd_send(args):
    delay = float(args[0]) if args else 0.0
    data = sys.stdin.buffer.read()
    if delay > 0:
        time.sleep(delay)
    payload = json.loads(data.decode("utf-8"))
    if payload.get("source") == "codex" and not payload.get("windows"):
        # для Codex окна читаем уже после паузы: лог мог дописаться
        payload["windows"] = codex_windows()
        if not payload["windows"]:
            return
    try:
        post(payload)
    except Exception:
        pass  # молча: ни statusline, ни Codex не должны страдать от сети


def mark_seen(source, **info):
    """Запоминаем, что хук вызывался — для push.py doctor."""
    try:
        os.makedirs(BASE, exist_ok=True)
        with open(os.path.join(BASE, f".seen-{source}.json"), "w", encoding="utf-8") as f:
            json.dump(dict(info, at=time.time()), f)
    except OSError:
        pass


def read_seen(source):
    try:
        with open(os.path.join(BASE, f".seen-{source}.json"), encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return None


# ---------------- Claude Code ----------------

CLAUDE_LABELS = {"five_hour": "5ч", "seven_day": "нед", "spend_limit": "бюджет"}


def claude_windows(status):
    rl = status.get("rate_limits") if isinstance(status, dict) else None
    if not isinstance(rl, dict):
        return []
    out = []
    for key, w in rl.items():
        if not isinstance(w, dict) or "used_percentage" not in w:
            continue
        out.append({
            "id": key,
            "label": CLAUDE_LABELS.get(key, key.replace("_", " ")),
            "used_percent": w.get("used_percentage"),
            "resets_at": w.get("resets_at"),
        })
    order = list(CLAUDE_LABELS)
    out.sort(key=lambda w: order.index(w["id"]) if w["id"] in order else 99)
    return out


def cmd_claude(args):
    raw = sys.stdin.buffer.read()
    try:
        status = json.loads(raw.decode("utf-8") or "{}")
    except ValueError:
        status = {}

    windows = claude_windows(status)
    mark_seen("claude", has_rate_limits=bool(windows),
              keys=sorted(status.keys()) if isinstance(status, dict) else [],
              version=status.get("version") if isinstance(status, dict) else None)
    if windows:
        send_detached({"source": "claude", "label": "Claude", "windows": windows})

    # Если раньше была своя статус-строка — показываем её вывод
    chain = None
    if len(args) >= 2 and args[0] == "--chain":
        chain = args[1]
    if chain:
        try:
            r = subprocess.run(chain, shell=True, input=raw, capture_output=True, timeout=10)
            sys.stdout.buffer.write(r.stdout)
            return
        except Exception:
            pass

    parts = []
    for w in windows:
        try:
            parts.append(f"{w['label']} {100 - float(w['used_percent']):.0f}%")
        except (TypeError, ValueError):
            pass
    model = (status.get("model") or {}).get("display_name") if isinstance(status, dict) else None
    line = " · ".join(([model] if model else []) + (["осталось " + ", ".join(parts)] if parts else []))
    print(line)


# ---------------- Codex ----------------

def _codex_label(minutes):
    if not isinstance(minutes, (int, float)) or minutes <= 0:
        return ""
    if minutes == 10080:
        return "нед"
    if minutes % 1440 == 0:
        return f"{int(minutes // 1440)}д"
    if minutes % 60 == 0:
        return f"{int(minutes // 60)}ч"
    return f"{int(minutes)}м"


def _newest_rollouts(limit=3):
    """Несколько самых свежих rollout-*.jsonl, без обхода всей истории."""
    if not os.path.isdir(CODEX_SESSIONS):
        return []
    found = []
    # раскладка: sessions/YYYY/MM/DD/rollout-*.jsonl — идём от новых дат
    for root, dirs, files in os.walk(CODEX_SESSIONS):
        dirs.sort(reverse=True)
        for name in files:
            if name.startswith("rollout-") and name.endswith(".jsonl"):
                p = os.path.join(root, name)
                try:
                    found.append((os.path.getmtime(p), p))
                except OSError:
                    pass
        if len(found) >= 20:
            break
    found.sort(reverse=True)
    return [p for _, p in found[:limit]]


def _tail_lines(path, max_bytes=512 * 1024):
    with open(path, "rb") as f:
        f.seek(0, os.SEEK_END)
        size = f.tell()
        f.seek(max(0, size - max_bytes))
        data = f.read()
    return data.decode("utf-8", "replace").splitlines()


def codex_windows():
    for path in _newest_rollouts():
        try:
            lines = _tail_lines(path)
        except OSError:
            continue
        for line in reversed(lines):
            if '"rate_limits"' not in line:
                continue
            try:
                ev = json.loads(line)
            except ValueError:
                continue
            payload = ev.get("payload") if isinstance(ev, dict) else None
            rl = payload.get("rate_limits") if isinstance(payload, dict) else None
            if not isinstance(rl, dict):
                continue
            out = []
            for key in ("primary", "secondary"):
                w = rl.get(key)
                if not isinstance(w, dict) or w.get("used_percent") is None:
                    continue
                resets = w.get("resets_at")
                if resets is None and isinstance(w.get("resets_in_seconds"), (int, float)):
                    # старые версии Codex писали относительное время
                    ts = ev.get("timestamp")
                    base = time.time()
                    if isinstance(ts, str):
                        try:
                            base = calendar.timegm(time.strptime(ts[:19], "%Y-%m-%dT%H:%M:%S"))
                        except ValueError:
                            pass
                    resets = int(base + w["resets_in_seconds"])
                out.append({
                    "id": key,
                    "label": _codex_label(w.get("window_minutes")),
                    "used_percent": w.get("used_percent"),
                    "resets_at": resets,
                })
            if out:
                return out
    return []


def cmd_scan(_args):
    """Периодический опрос лога Codex (работает и для приложения Codex, без хуков)."""
    w = codex_windows()
    mark_seen("scan", found=bool(w))
    if not w:
        return
    payload = {"source": "codex", "label": "Codex", "windows": w}
    if should_send("codex", payload):
        try:
            post(payload)
        except Exception:
            pass


def cmd_codex(args):
    # Codex передаёт JSON события последним аргументом; нам он не нужен —
    # данные о лимитах берём из лога, чуть подождав, пока он допишется.
    chain = []
    if "--" in args:
        i = args.index("--")
        chain, args = args[i + 1:], args[:i]
    mark_seen("codex")
    send_detached({"source": "codex", "label": "Codex", "windows": []}, delay=1.5)
    if chain:
        try:
            subprocess.Popen(chain + args, start_new_session=True,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        except OSError:
            pass


# ---------------- установка ----------------

def _q(s):
    return "'" + s.replace("'", "'\"'\"'") + "'"


def install_claude():
    os.makedirs(os.path.dirname(CLAUDE_SETTINGS), exist_ok=True)
    settings = {}
    if os.path.exists(CLAUDE_SETTINGS):
        with open(CLAUDE_SETTINGS, encoding="utf-8") as f:
            settings = json.load(f)
        shutil.copy2(CLAUDE_SETTINGS, CLAUDE_SETTINGS + ".infowall-backup")

    old = settings.get("statusLine") or {}
    old_cmd = old.get("command") if isinstance(old, dict) else None
    cmd = f"{_q(PY)} {_q(INSTALLED)} claude"
    if old_cmd and MARK in old_cmd:
        print("  Claude Code: хук уже стоит")
        return
    if old_cmd:
        cmd += f" --chain {_q(old_cmd)}"
        print("  Claude Code: твоя статус-строка сохранена и будет показываться как раньше")
    new = dict(old) if isinstance(old, dict) else {}
    new.update({"type": "command", "command": cmd})
    settings["statusLine"] = new
    with open(CLAUDE_SETTINGS, "w", encoding="utf-8") as f:
        json.dump(settings, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"  Claude Code: statusLine прописан в {CLAUDE_SETTINGS}")


NOTIFY_RE = re.compile(r"^\s*notify\s*=", re.M)


def install_codex():
    if not os.path.isdir(os.path.join(HOME, ".codex")):
        print("  Codex: ~/.codex не найден — пропускаю")
        return
    text = ""
    if os.path.exists(CODEX_CONFIG):
        with open(CODEX_CONFIG, encoding="utf-8") as f:
            text = f.read()
    m = NOTIFY_RE.search(text)
    if m:
        line = text[m.start():text.find("\n", m.start()) if "\n" in text[m.start():] else len(text)]
        if MARK in line:
            print("  Codex: хук уже стоит")
            return
        print("  Codex: в config.toml уже есть свой notify — не трогаю (лимиты возьмёт фоновый опрос)")
        return
    if text:
        shutil.copy2(CODEX_CONFIG, CODEX_CONFIG + ".infowall-backup")
    # notify — ключ верхнего уровня, поэтому ставим его до первой [секции]
    line = f'notify = [{json.dumps(PY)}, {json.dumps(INSTALLED)}, "codex"]  # {MARK}\n'
    with open(CODEX_CONFIG, "w", encoding="utf-8") as f:
        f.write(line + ("\n" if text and not text.startswith("\n") else "") + text)
    print(f"  Codex: notify прописан в {CODEX_CONFIG}")


AGENT_ID = "com.kayo.infowall.scan"
AGENT_PLIST = os.path.join(HOME, "Library", "LaunchAgents", AGENT_ID + ".plist")


def install_agent():
    """macOS: раз в минуту запускать `push.py scan` — без хуков в Codex."""
    if sys.platform != "darwin":
        print("  Фоновый опрос: только для macOS — пропускаю (хватит notify)")
        return
    if not os.path.isdir(os.path.join(HOME, ".codex")):
        return
    import plistlib
    os.makedirs(os.path.dirname(AGENT_PLIST), exist_ok=True)
    with open(AGENT_PLIST, "wb") as f:
        plistlib.dump({
            "Label": AGENT_ID,
            "ProgramArguments": [PY, INSTALLED, "scan"],
            "StartInterval": 60,
            "RunAtLoad": True,
            "ProcessType": "Background",
            "LowPriorityIO": True,
            "StandardErrorPath": os.path.join(BASE, "scan.log"),
        }, f)
    uid = str(os.getuid())
    subprocess.run(["launchctl", "bootout", f"gui/{uid}/{AGENT_ID}"],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    r = subprocess.run(["launchctl", "bootstrap", f"gui/{uid}", AGENT_PLIST],
                       capture_output=True, text=True)
    if r.returncode == 0:
        print("  Codex: фоновый опрос лога раз в минуту включён (работает и с приложением Codex)")
    else:
        print(f"  Codex: не смог включить фоновый опрос: {r.stderr.strip() or r.returncode}")


def uninstall_agent():
    if sys.platform == "darwin" and os.path.exists(AGENT_PLIST):
        subprocess.run(["launchctl", "bootout", f"gui/{os.getuid()}/{AGENT_ID}"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        os.remove(AGENT_PLIST)
        print("Фоновый опрос Codex: выключен")


def cmd_install(args):
    url = token = None
    it = iter(args)
    for a in it:
        if a == "--url":
            url = next(it, None)
        elif a == "--token":
            token = next(it, None)
    if not url or not token:
        saved_url, saved_token = load_config()
        url, token = url or saved_url, token or saved_token
    if not url or not token:
        sys.exit("usage: push.py install --url https://host:port --token WRITE_TOKEN")

    os.makedirs(BASE, exist_ok=True)
    if os.path.abspath(__file__) != INSTALLED:
        shutil.copy2(os.path.abspath(__file__), INSTALLED)
    with open(CONFIG, "w", encoding="utf-8") as f:
        json.dump({"url": url.rstrip("/"), "token": token}, f)
    os.chmod(CONFIG, 0o600)
    print(f"Установлено в {BASE}")

    install_claude()
    install_codex()
    install_agent()

    try:
        # пустой список окон = «удалить источник», т.е. безвредная проверка
        code = post({"source": "push-test", "label": "Проверка", "windows": []})
        print(f"Связь с сервером: OK (HTTP {code})")
    except Exception as e:
        print(f"Связь с сервером: ОШИБКА — {e}")
    print("Готово. Лимиты появятся после следующего ответа в Claude Code / Codex.")


def cmd_uninstall(_args):
    uninstall_agent()
    if os.path.exists(CLAUDE_SETTINGS):
        with open(CLAUDE_SETTINGS, encoding="utf-8") as f:
            s = json.load(f)
        cmd = (s.get("statusLine") or {}).get("command", "")
        if MARK in cmd:
            m = re.search(r"--chain\s+'((?:[^']|'\"'\"')*)'", cmd)
            if m:
                s["statusLine"]["command"] = m.group(1).replace("'\"'\"'", "'")
            else:
                s.pop("statusLine", None)
            with open(CLAUDE_SETTINGS, "w", encoding="utf-8") as f:
                json.dump(s, f, ensure_ascii=False, indent=2)
                f.write("\n")
            print("Claude Code: хук убран")
    if os.path.exists(CODEX_CONFIG):
        with open(CODEX_CONFIG, encoding="utf-8") as f:
            lines = f.readlines()
        kept, skip_blank = [], False
        for l in lines:
            if NOTIFY_RE.match(l) and MARK in l:
                skip_blank = True   # и пустую строку, которую добавил install
                continue
            if skip_blank and not l.strip():
                skip_blank = False
                continue
            skip_blank = False
            kept.append(l)
        if len(kept) != len(lines):
            with open(CODEX_CONFIG, "w", encoding="utf-8") as f:
                f.writelines(kept)
            print("Codex: хук убран")


def cmd_test(args):
    if "--clear" in args:
        print("HTTP", post({"source": "test", "windows": []}), "— тестовые карточки убраны")
        return
    now = int(time.time())
    print("HTTP", post({"source": "test", "label": "Тест", "windows": [
        {"id": "a", "label": "5ч", "used_percent": 35, "resets_at": now + 2 * 3600},
        {"id": "b", "label": "нед", "used_percent": 92, "resets_at": now + 3 * 86400},
    ]}))


def _ago(ts):
    d = int(time.time() - ts)
    if d < 90:
        return f"{d} сек назад"
    if d < 5400:
        return f"{d // 60} мин назад"
    return f"{d // 3600} ч назад"


def cmd_doctor(_args):
    ok, bad = "✓", "✗"
    url, token = load_config()
    print(f"{ok if url and token else bad} конфиг: {url or 'нет — запусти install'}")
    if url and token:
        try:
            post({"source": "push-test", "windows": []})
            print(f"{ok} сервер принимает данные")
        except Exception as e:
            print(f"{bad} сервер: {e}")

    # --- Claude Code ---
    cmd = ""
    try:
        with open(CLAUDE_SETTINGS, encoding="utf-8") as f:
            cmd = (json.load(f).get("statusLine") or {}).get("command", "")
    except (OSError, ValueError):
        pass
    print(f"{ok if MARK in cmd else bad} Claude Code: statusLine {'прописан' if MARK in cmd else 'НЕ прописан'}")
    seen = read_seen("claude")
    if not seen:
        print(f"{bad} Claude Code: статус-строка ещё не вызывала скрипт — открой claude в терминале "
              "и отправь сообщение (приложение Claude и claude.ai лимиты не отдают)")
    else:
        print(f"{ok} Claude Code: вызывал скрипт {_ago(seen['at'])}, версия {seen.get('version') or '?'}")
        if seen.get("has_rate_limits"):
            print(f"{ok} Claude Code: лимиты приходят")
        else:
            print(f"{bad} Claude Code: лимитов в данных нет — нужен вход по подписке Pro/Max "
                  "(не API-ключ), свежая версия (claude update) и хотя бы один ответ в сессии")

    # --- Codex ---
    if os.path.isdir(os.path.join(HOME, ".codex")):
        txt = ""
        try:
            with open(CODEX_CONFIG, encoding="utf-8") as f:
                txt = f.read()
        except OSError:
            pass
        hooked = any(NOTIFY_RE.match(l) and MARK in l for l in txt.splitlines())
        scan = read_seen("scan")
        if sys.platform == "darwin":
            agent = os.path.exists(AGENT_PLIST)
            print(f"{ok if agent else bad} Codex: фоновый опрос " +
                  (f"включён, последний запуск {_ago(scan['at'])}" if agent and scan else
                   "включён, ещё не запускался" if agent else
                   "выключен — запусти: python3 ~/.infowall/push.py install"))
        seen = read_seen("codex")
        if hooked or seen:
            print(f"{ok if seen else '–'} Codex CLI: notify " +
                  (f"вызывался {_ago(seen['at'])}" if seen else "прописан, ещё не вызывался"))
        rolls = _newest_rollouts(1)
        print(f"{ok if rolls else bad} Codex: лог сессий " + (_ago(os.path.getmtime(rolls[0])) if rolls else "не найден"))
        w = codex_windows()
        if w:
            print(f"{ok} Codex: лимиты в логе есть — " + ", ".join(f"{x['label']} {x['used_percent']}%" for x in w))
            try:
                post({"source": "codex", "label": "Codex", "windows": w})
                print(f"{ok} Codex: отправил на сервер прямо сейчас")
            except Exception as e:
                print(f"{bad} Codex: не отправилось: {e}")
        else:
            print(f"{bad} Codex: лимитов в логах не нашёл")
    else:
        print("– Codex: не установлен")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return
    cmd, args = sys.argv[1], sys.argv[2:]
    {
        "claude": cmd_claude,
        "codex": cmd_codex,
        "install": cmd_install,
        "uninstall": cmd_uninstall,
        "test": cmd_test,
        "doctor": cmd_doctor,
        "scan": cmd_scan,
        "_send": cmd_send,
    }.get(cmd, lambda _a: sys.exit(f"неизвестная команда: {cmd}"))(args)


if __name__ == "__main__":
    main()
