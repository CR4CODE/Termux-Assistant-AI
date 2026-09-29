#!/usr/bin/env python3
import socket, datetime, sys
LOG = "receiver.log"
srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
srv.bind(("127.0.0.1", 8765))
srv.listen(5)
with open(LOG, "a", buffering=1) as f:
    f.write(f"\n=== receiver started {datetime.datetime.now()} ===\n")
    while True:
        try:
            cli, _ = srv.accept()
            data = b""
            while True:
                c = cli.recv(4096)
                if not c: break
                data += c
            cli.close()
            for line in data.decode("utf-8", "replace").strip().split("\n"):
                if line:
                    f.write(f"[{datetime.datetime.now().strftime('%H:%M:%S')}] {line}\n")
        except Exception as e:
            f.write(f"ERR: {e}\n")
