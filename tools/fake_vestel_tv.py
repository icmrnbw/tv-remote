"""A pretend Vestel Smart Center TV on 127.0.0.1:56789 that logs what it receives.

    python fake_vestel_tv.py
"""

from http.server import BaseHTTPRequestHandler, HTTPServer


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("latin-1")
        print("TV:", self.path, repr(body), self.headers.get("Content-Type"), flush=True)
        self.send_response(201 if self.path.startswith("/apps/") and self.path != "/apps/SmartCenter" else 200)
        self.end_headers()

    def log_message(self, *args):
        pass


HTTPServer(("127.0.0.1", 56789), Handler).serve_forever()
