#!/usr/bin/env python3
"""
InfoWall agent — показывает обоям состояние сервера: CPU, RAM, диск, аптайм.

Только чтение, только GET /metrics, только по токену и только по HTTPS
(самоподписанный сертификат, обои доверяют ровно его отпечатку).
Стандартная библиотека Python 3.8+, без зависимостей. Linux (/proc).

Настройки — /etc/infowall-agent/agent.json:
  {"port": 8790, "token": "...", "cert": ".../cert.pem", "key": ".../key.pem"}
"""

import hmac
import json
import os
import socket
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CONF = os.environ.get("INFOWALL_AGENT_CONF", "/etc/infowall-agent/agent.json")
SAMPLE_SEC = 5
RATE_PER_MIN = 30          # запросов в минуту с одного адреса

_lock = threading.Lock()
_snapshot = {}
_hits = {}


# ---------- сбор метрик ----------

def _cpu_times():
    with open("/proc/stat") as f:
        parts = f.readline().split()[1:]
    vals = [int(x) for x in parts]
    idle = vals[3] + (vals[4] if len(vals) > 4 else 0)   # idle + iowait
    return sum(vals), idle


def _mem():
    info = {}
    with open("/proc/meminfo") as f:
        for line in f:
            k, v = line.split(":", 1)
            info[k] = int(v.split()[0]) * 1024
    total = info.get("MemTotal", 0)
    avail = info.get("MemAvailable", info.get("MemFree", 0))
    return total - avail, total


def _disk():
    st = os.statvfs("/")
    total = st.f_blocks * st.f_frsize
    free = st.f_bavail * st.f_frsize
    return total - free, total


def _uptime():
    with open("/proc/uptime") as f:
        return int(float(f.read().split()[0]))


def sampler():
    prev = _cpu_times()
    while True:
        time.sleep(SAMPLE_SEC)
        try:
            cur = _cpu_times()
            dt, di = cur[0] - prev[0], cur[1] - prev[1]
            prev = cur
            cpu = 0.0 if dt <= 0 else max(0.0, min(100.0, 100.0 * (dt - di) / dt))
            mu, mt = _mem()
            du, dtot = _disk()
            snap = {
                "host": socket.gethostname(),
                "cpu": round(cpu, 1),
                "ram_used": mu, "ram_total": mt,
                "disk_used": du, "disk_total": dtot,
                "uptime": _uptime(),
                "load": list(os.getloadavg()),
                "at": int(time.time()),
            }
            with _lock:
                _snapshot.clear()
                _snapshot.update(snap)
        except Exception as e:  # метрика не должна ронять агента
            print("sample error:", e, file=sys.stderr, flush=True)


# ---------- HTTP ----------

class Handler(BaseHTTPRequestHandler):
    server_version = "InfoWallAgent/1"
    sys_version = ""
    token = ""
    timeout = 10   # медленный клиент не держит поток дольше 10 секунд

    def log_message(self, fmt, *args):
        pass   # без логов запросов: агент и так ничего не меняет

    def _send(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _limited(self):
        ip = self.client_address[0]
        now = time.time()
        with _lock:
            hits = [t for t in _hits.get(ip, []) if now - t < 60]
            hits.append(now)
            _hits[ip] = hits
            if len(_hits) > 1000:
                _hits.clear()
        return len(hits) > RATE_PER_MIN

    def do_GET(self):
        if self._limited():
            return self._send(429, {"error": "slow down"})
        if self.path.split("?", 1)[0] != "/metrics":
            return self._send(404, {"error": "not found"})
        auth = self.headers.get("Authorization", "")
        given = auth[7:].strip() if auth.startswith("Bearer ") else ""
        if not given or not hmac.compare_digest(given.encode(), self.token.encode()):
            return self._send(401, {"error": "unauthorized"})
        with _lock:
            snap = dict(_snapshot)
        if not snap:
            return self._send(503, {"error": "warming up"})
        self._send(200, snap)

    def do_POST(self):
        self._send(405, {"error": "read only"})

    do_PUT = do_DELETE = do_PATCH = do_POST


def main():
    with open(CONF) as f:
        conf = json.load(f)
    token = conf["token"]
    if len(token) < 24:
        sys.exit("token too short")
    Handler.token = token
    threading.Thread(target=sampler, daemon=True).start()

    srv = ThreadingHTTPServer(("0.0.0.0", int(conf.get("port", 8790))), Handler)
    srv.daemon_threads = True
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(conf["cert"], conf["key"])
    # TLS-рукопожатие — в потоке запроса, а не в цикле accept: медленный клиент не блокирует остальных
    srv.socket = ctx.wrap_socket(srv.socket, server_side=True, do_handshake_on_connect=False)
    print("InfoWall agent on port", conf.get("port", 8790), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
