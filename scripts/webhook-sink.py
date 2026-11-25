#!/usr/bin/env python3
"""Minimal webhook sink for local development and the smoke test.

Records every request made to any path other than /received (method, path,
headers, body, and body_b64 = the exact raw bytes, base64) in memory and answers 200. GET /received returns the recorded
requests as a JSON array. Two deterministic routes (also recorded):
  /status/<code>          answers with that HTTP status code
  /flaky/<key>?fail=<n>   answers 500 for the first n requests per key, then 200
Listens on 0.0.0.0:${PORT:-8080}. Stdlib only.
"""
import base64
import json
import os
import threading
from urllib.parse import parse_qs, urlsplit
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RECEIVED = []
FLAKY_COUNTS = {}
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send(self, status, body=b"", content_type="text/plain"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def _handle(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length > 0 else b""
        path_only = self.path.split("?", 1)[0]
        if self.command == "GET" and path_only == "/received":
            with LOCK:
                data = json.dumps(RECEIVED).encode()
            self._send(200, data, "application/json")
            return
        record = {
            "method": self.command,
            "path": self.path,
            "headers": {k: v for k, v in self.headers.items()},
            "body": raw.decode("utf-8", errors="replace"),
            "body_b64": base64.b64encode(raw).decode("ascii"),
        }
        with LOCK:
            RECEIVED.append(record)
        self._send(self._status_for(path_only), b"ok")

    def _status_for(self, path_only):
        parts = path_only.strip("/").split("/")
        if len(parts) == 2 and parts[0] == "status":
            try:
                code = int(parts[1])
            except ValueError:
                return 200
            return code if 200 <= code <= 599 else 200
        if len(parts) == 2 and parts[0] == "flaky":
            try:
                fail = int(parse_qs(urlsplit(self.path).query).get("fail", ["0"])[0])
            except ValueError:
                fail = 0
            with LOCK:
                seen = FLAKY_COUNTS.get(parts[1], 0)
                FLAKY_COUNTS[parts[1]] = seen + 1
            return 500 if seen < fail else 200
        return 200

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = do_OPTIONS = _handle

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
