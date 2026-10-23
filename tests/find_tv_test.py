#!/usr/bin/env python3
"""optimize/find-tv.py --check on three simulated networks: a home Wi-Fi, a sandbox whose proxy accepts every
connection, and a virtual machine with no route out. Run: python3 tests/find_tv_test.py"""
import errno
import importlib.util
import io
import socket
import sys
from contextlib import redirect_stdout
from pathlib import Path

spec = importlib.util.spec_from_file_location("find_tv", Path(__file__).resolve().parents[1] / "optimize/find-tv.py")
ft = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ft)
failed = 0


def check(name, ok, detail=""):
    global failed
    print(("PASS " if ok else "FAIL ") + name + ("" if ok else f"  -> {detail}"))
    failed += not ok


def run(ip, answer):
    """answer(host, port) -> "open" | "refused" | "timeout" | "unreachable" (errno or winerror)."""
    def connect(addr, timeout=None):
        a = answer(*addr)
        if a == "open":
            return socket.socket()
        if a == "refused":
            raise ConnectionRefusedError()
        if a == "unreachable":
            raise OSError(errno.ENETUNREACH, "Network is unreachable")
        if a == "winunreachable":
            e = OSError(None, "A socket operation was attempted to an unreachable network")
            e.winerror = 10051
            raise e
        raise TimeoutError()
    ft.local_ip, socket.create_connection = (lambda: ip), connect
    out = io.StringIO()
    with redirect_stdout(out):
        code = ft.check()
    return code, out.getvalue()


real = socket.create_connection
try:
    # home Wi-Fi: the router answers on 80, unused ports are refused or silent, other ranges time out
    home = lambda h, p: "open" if (h, p) == ("192.168.1.1", 80) else "refused" if h.startswith("192.168.1.") else "timeout"
    code, out = run("192.168.1.23", home)
    check("home Wi-Fi: OK", code == 0, out)
    # a sandbox: the proxy accepts any address and port
    code, out = run("192.168.1.23", lambda h, p: "open")
    check("sandbox proxy answering everything: not OK", code == 1 and "proxy" in out, out)
    # a virtual machine on this computer: its own address, no route to the home network
    code, out = run("172.17.0.2", lambda h, p: "unreachable")
    check("virtual machine, no route: not OK", code == 1 and "unreachable" in out, out)
    code, out = run("10.0.2.15", lambda h, p: "winunreachable")
    check("no route on Windows (WinError 10051): not OK", code == 1 and "unreachable" in out, out)
    # a cloud container: not a home address at all
    code, out = run("192.0.2.2", lambda h, p: "open")
    check("cloud container address: not OK", code == 1 and "not a home network address" in out, out)
finally:
    socket.create_connection = real
print("\nall find-tv checks passed" if not failed else f"\n{failed} failed")
sys.exit(1 if failed else 0)
