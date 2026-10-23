#!/usr/bin/env python3
"""Find the TV on this computer's Wi-Fi. Standard library only, so it runs on Windows, macOS and Linux before
optimize/setup.sh, with whatever `python3` or `python` the computer has.

  optimize/find-tv.sh --check   (or: python optimize/find-tv.py --check)   can this terminal reach the home network?
  optimize/find-tv.sh                                                     scan for TVs with ADB (5555) and Cast (8008); saves the first to optimize/.tv_host

ADB needs a direct TCP connection to the TV. An agent whose commands run in a cloud container, a sandbox or a
virtual machine (even one running on this computer) usually has no route to the home network: the check says so
plainly, and then no amount of retrying will find the TV.
"""
import concurrent.futures
import errno
import ipaddress
import json
import os
import socket
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
HOST_FILE = os.path.join(HERE, ".tv_host")


HOME = [ipaddress.ip_network(n) for n in ("192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12")]


def local_ip():
    """The address this computer uses to go out (no packet is sent: UDP connect only picks the route)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("1.1.1.1", 9))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def home(ip):
    return bool(ip) and any(ipaddress.ip_address(ip) in n for n in HOME)


def fake_network(net):
    """A sandbox's proxy accepts a connection to any address. Knock where nothing can be: another home range than
    ours, on an unused port. A real network never accepts that; a proxy does."""
    other = "10.254.254.254" if not net.startswith("10.") else "192.168.254.254"
    return connects(other, 47813, 1.0) or connects(f"{net}.254", 47813, 1.0) and connects(f"{net}.253", 47813, 1.0)


def reachable(host, port, timeout=1.5):
    """True: something answered (even «refused» proves the route works). False: no route, or nothing there."""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except ConnectionRefusedError:
        return True
    except OSError:
        return False


def no_route(host, port=80):
    try:
        socket.create_connection((host, port), timeout=1.5).close()
        return False
    except OSError as e:
        return e.errno in (errno.ENETUNREACH, errno.EHOSTUNREACH) or getattr(e, "winerror", None) in (10051, 10065)


def check():
    ip = local_ip()
    print(f"this terminal's address: {ip or 'none'}")
    if not home(ip):
        return verdict(False, f"{ip or 'no address'} is not a home network address (192.168.x, 10.x, 172.16-31.x)")
    net = ip.rsplit(".", 1)[0]
    if fake_network(net):
        return verdict(False, "addresses that cannot exist answer too, so a proxy answers, not your network")
    router = f"{net}.1"
    for port in (80, 443, 53):
        if reachable(router, port):
            return verdict(True, f"the router {router} answers")
    if no_route(router):
        return verdict(False, f"Network is unreachable ({router})")
    # some routers answer nothing: look for any device on the /24
    with concurrent.futures.ThreadPoolExecutor(64) as pool:
        hits = [h for h, ok in zip(range(1, 255), pool.map(lambda i: reachable(f"{net}.{i}", 80, 0.7), range(1, 255))) if ok]
    if hits:
        return verdict(True, f"devices answer on {net}.0/24")
    return verdict(False, f"nothing answers on {net}.0/24 (a sandbox or virtual machine's own network?)")


def verdict(ok, why):
    if ok:
        print(f"OK: this terminal can reach the home network ({why}). Next: optimize/find-tv.sh")
        return 0
    print(f"!! This terminal cannot reach the home network: {why}.")
    print("   It runs somewhere else (a cloud session, a sandbox, a virtual machine): it can never reach the TV.")
    print("   Use an agent that runs commands directly on a computer on the TV's Wi-Fi, for example Claude Code")
    print("   installed on that computer (Windows: in Git Bash or PowerShell; Mac: in Terminal).")
    return 1


def cast_name(host):
    try:
        url = f"http://{host}:8008/setup/eureka_info?params=name"
        with urllib.request.urlopen(url, timeout=3) as r:
            return json.load(r).get("name", "")
    except Exception:  # noqa: BLE001
        return ""


def scan():
    ip = local_ip()
    net = ip.rsplit(".", 1)[0] if home(ip) else None
    if not net or fake_network(net):
        print("!! This terminal cannot reach the home network. Run: optimize/find-tv.sh --check")
        return 1
    print(f"Scanning {net}.0/24 from {ip} …")
    hosts = [f"{net}.{i}" for i in range(1, 255)]
    with concurrent.futures.ThreadPoolExecutor(128) as pool:
        adb = [h for h, ok in zip(hosts, pool.map(lambda h: connects(h, 5555), hosts)) if ok]
        cast = [h for h, ok in zip(hosts, pool.map(lambda h: connects(h, 8008), hosts)) if ok]
    for h in cast:
        name = cast_name(h)
        print(f"Cast device: {h}" + (f" ({name})" if name else ""))
    if adb:
        print("ADB open on: " + " ".join(adb))
        with open(HOST_FILE, "w") as f:
            f.write(adb[0] + "\n")
        print(f"Saved {adb[0]} to optimize/.tv_host. Next: optimize/venv/bin/python -I optimize/tv.py --probe")
        return 0
    print("No TV with ADB open. On the TV: Developer options → USB debugging (and Network debugging) on, TV awake.")
    if cast:
        print("(The TV is probably one of the Cast devices above: set TV_HOST=<its address> after turning debugging on.)")
    return 1


def connects(host, port, timeout=1.0):
    """Only a real open port (an accepted connection), not «refused»."""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


if __name__ == "__main__":
    sys.exit(check() if "--check" in sys.argv[1:] else scan())
