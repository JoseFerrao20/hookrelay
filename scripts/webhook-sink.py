#!/usr/bin/env python3
"""Minimal webhook sink for local development and the smoke test.

Records every request made to any path other than /received (method, path,
headers, body) in memory and answers 200. GET /received returns the recorded
requests as a JSON array. Listens on 0.0.0.0:${PORT:-8080}. Stdlib only.
"""
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

RECEIVED = []
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
        }
        with LOCK:
            RECEIVED.append(record)
        self._send(200, b"ok")

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = do_HEAD = do_OPTIONS = _handle

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "8080"))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
