"""A pretend TV like the user's Rosso: DIAL on 56789 (no Smart Center) and a DLNA
renderer on 2870 with volume control. Binds 127.0.0.1 and logs what it receives.

    python fake_dial_tv.py
"""

import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

state = {"volume": 20, "mute": 0}

DMR = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><device>
<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
<friendlyName>Smart TV</friendlyName><modelDescription>IPI Media Renderer</modelDescription>
<serviceList>
<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
<controlURL>/AVTransport/control</controlURL></service>
<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
<controlURL>RenderingControl/control</controlURL></service>
</serviceList></device></root>"""


class Dial(BaseHTTPRequestHandler):
    def do_GET(self):
        code = 404 if self.path == "/apps/SmartCenter" else 200
        print("DIAL:", "GET", self.path, code, flush=True)
        self.send_response(code)
        self.end_headers()

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        print("DIAL:", "POST", self.path, flush=True)
        self.send_response(201)
        self.send_header("Location", f"http://127.0.0.1:56789{self.path}/run")
        self.end_headers()

    def do_DELETE(self):
        print("DIAL:", "DELETE", self.path, flush=True)
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass


class Dlna(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/dmr.xml":
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.end_headers()
        self.wfile.write(DMR.encode())

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode()
        action = self.headers.get("SOAPACTION", "").strip('"').split("#")[-1]
        assert self.path == "/RenderingControl/control", self.path
        assert "<Channel>Master</Channel>" in body and "<InstanceID>0</InstanceID>" in body
        if action == "SetVolume":
            state["volume"] = int(re.search(r"<DesiredVolume>(\d+)<", body).group(1))
        if action == "SetMute":
            state["mute"] = int(re.search(r"<DesiredMute>(\d)<", body).group(1))
        print("DLNA:", action, state, flush=True)
        # Like the user's TV: GetVolume answers 0 even though the volume is set.
        reported = 0 if action == "GetVolume" else state["volume"]
        reply = (f"<u:{action}Response><CurrentVolume xmlns:dt=\"urn:schemas-microsoft-com:datatypes\" dt:dt=\"ui2\">{reported}</CurrentVolume>"
                 f"<CurrentMute>{state['mute']}</CurrentMute></u:{action}Response>")
        self.send_response(200)
        self.end_headers()
        self.wfile.write(f"<s:Envelope><s:Body>{reply}</s:Body></s:Envelope>".encode())

    def log_message(self, *args):
        pass


threading.Thread(target=ThreadingHTTPServer(("127.0.0.1", 56789), Dial).serve_forever, daemon=True).start()
ThreadingHTTPServer(("127.0.0.1", 2870), Dlna).serve_forever()
