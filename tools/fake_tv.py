"""A pretend Android TV for testing the app's protocol code without a real TV.

Uses the protobuf definitions shipped with the androidtvremote2 package
(pip install androidtvremote2). Listens on the real ports 6467 (pairing) and
6466 (remote), so run it on a machine where those are free.

    python fake_tv.py CLIENT_CERT_PEM WORK_DIR

The pairing code is printed and written to WORK_DIR/code.txt. The remote port
only accepts the client certificate after pairing succeeded.
"""

import hashlib
import os
import socket
import ssl
import sys
import threading

from androidtvremote2.certificate_generator import generate_selfsigned_cert
from androidtvremote2.polo_pb2 import OuterMessage, Options
from androidtvremote2.remotemessage_pb2 import RemoteMessage
from cryptography import x509
from google.protobuf.internal.decoder import _DecodeVarint
from google.protobuf.internal.encoder import _VarintBytes

client_pem_path, work = sys.argv[1], sys.argv[2]
cert_pem, key_pem = generate_selfsigned_cert("fake-tv")
open(os.path.join(work, "tv_cert.pem"), "wb").write(cert_pem)
open(os.path.join(work, "tv_key.pem"), "wb").write(key_pem)
server_cert = x509.load_pem_x509_certificate(cert_pem)
paired = threading.Event()
done = threading.Event()


def context(trusted_pem):
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(os.path.join(work, "tv_cert.pem"), os.path.join(work, "tv_key.pem"))
    ctx.verify_mode = ssl.CERT_REQUIRED
    ctx.load_verify_locations(cadata=trusted_pem.decode())
    return ctx


client_pem = open(client_pem_path, "rb").read()
trusting_client = context(client_pem)
trusting_nobody = context(cert_pem)  # only trusts the TV itself, so the phone is rejected


def read(conn):
    buf = b""
    while True:
        b = conn.recv(1)
        if not b:
            raise EOFError
        buf += b
        if not b[0] & 0x80:
            break
    length, _ = _DecodeVarint(buf, 0)
    data = b""
    while len(data) < length:
        chunk = conn.recv(length - len(data))
        if not chunk:
            raise EOFError
        data += chunk
    return data


def write(conn, msg):
    data = msg.SerializeToString()
    conn.sendall(_VarintBytes(len(data)) + data)


def outer(**kw):
    m = OuterMessage(protocol_version=2, status=OuterMessage.Status.STATUS_OK, **kw)
    return m


def pairing_server():
    srv = socket.create_server(("127.0.0.1", 6467))
    while not paired.is_set():
        raw, _ = srv.accept()
        conn = trusting_client.wrap_socket(raw, server_side=True)
        try:
            m = OuterMessage.FromString(read(conn))
            assert m.HasField("pairing_request"), m
            print("TV: pairing request from", m.pairing_request.client_name, "service", m.pairing_request.service_name)
            write(conn, outer(pairing_request_ack=OuterMessage().pairing_request_ack))
            m = OuterMessage.FromString(read(conn))
            assert m.HasField("options"), m
            assert m.options.input_encodings[0].type == Options.Encoding.ENCODING_TYPE_HEXADECIMAL
            assert m.options.input_encodings[0].symbol_length == 6
            assert m.options.preferred_role == Options.RoleType.ROLE_TYPE_INPUT
            reply = outer()
            enc = reply.options.input_encodings.add()
            enc.type = Options.Encoding.ENCODING_TYPE_HEXADECIMAL
            enc.symbol_length = 6
            reply.options.preferred_role = Options.RoleType.ROLE_TYPE_INPUT
            write(conn, reply)
            m = OuterMessage.FromString(read(conn))
            assert m.HasField("configuration"), m
            assert m.configuration.client_role == Options.RoleType.ROLE_TYPE_INPUT
            reply = outer()
            reply.configuration_ack.SetInParent()
            write(conn, reply)

            client = x509.load_der_x509_certificate(conn.getpeercert(binary_form=True)).public_key().public_numbers()
            server = server_cert.public_key().public_numbers()
            suffix = os.urandom(2)
            h = hashlib.sha256()
            h.update(bytes.fromhex(f"{client.n:X}"))
            h.update(bytes.fromhex(f"0{client.e:X}"))
            h.update(bytes.fromhex(f"{server.n:X}"))
            h.update(bytes.fromhex(f"0{server.e:X}"))
            h.update(suffix)
            expected = h.digest()
            code = f"{expected[0]:02X}{suffix.hex().upper()}"
            print("TV: showing pairing code", code)
            open(os.path.join(work, "code.txt"), "w").write(code)

            m = OuterMessage.FromString(read(conn))
            if m.secret.secret != expected:
                print("TV: wrong secret")
                write(conn, OuterMessage(protocol_version=2, status=OuterMessage.Status.STATUS_BAD_SECRET))
                continue
            reply = outer()
            reply.secret_ack.secret = expected
            write(conn, reply)
            print("TV: paired")
            paired.set()
        except (EOFError, ssl.SSLError, ConnectionError) as e:
            print("TV: pairing connection ended:", repr(e))
        finally:
            conn.close()


def remote_server():
    srv = socket.create_server(("127.0.0.1", 6466))
    while True:
        raw, _ = srv.accept()
        ctx = trusting_client if paired.is_set() else trusting_nobody
        try:
            conn = ctx.wrap_socket(raw, server_side=True)
        except ssl.SSLError as e:
            print("TV: rejected unpaired phone:", e.reason)
            raw.close()
            continue
        try:
            m = RemoteMessage()
            m.remote_configure.code1 = 622
            m.remote_configure.device_info.vendor = "Rosso"
            m.remote_configure.device_info.model = "Fake TV"
            write(conn, m)
            r = RemoteMessage.FromString(read(conn))
            assert r.HasField("remote_configure"), r
            print("TV: phone configured, features", r.remote_configure.code1, "package", r.remote_configure.device_info.package_name)
            m = RemoteMessage()
            m.remote_set_active.SetInParent()
            write(conn, m)
            r = RemoteMessage.FromString(read(conn))
            assert r.HasField("remote_set_active"), r
            print("TV: set active", r.remote_set_active.active)
            m = RemoteMessage()
            m.remote_start.started = True
            write(conn, m)
            m = RemoteMessage()
            m.remote_set_volume_level.volume_level = 12
            m.remote_set_volume_level.volume_max = 100
            write(conn, m)
            m = RemoteMessage()
            m.remote_ping_request.val1 = 7
            write(conn, m)
            r = RemoteMessage.FromString(read(conn))
            assert r.remote_ping_response.val1 == 7, r
            print("TV: ping answered")
            while True:
                r = RemoteMessage.FromString(read(conn))
                if r.HasField("remote_key_inject"):
                    print("TV: key", r.remote_key_inject.key_code, "direction", r.remote_key_inject.direction)
                elif r.HasField("remote_app_link_launch_request"):
                    print("TV: launch", r.remote_app_link_launch_request.app_link)
                else:
                    print("TV: other", r)
        except (EOFError, ConnectionError, ssl.SSLError) as e:
            print("TV: remote connection ended:", repr(e))
            done.set()
        finally:
            conn.close()


threading.Thread(target=pairing_server, daemon=True).start()
threading.Thread(target=remote_server, daemon=True).start()
print("TV: ready", flush=True)
done.wait(120)
