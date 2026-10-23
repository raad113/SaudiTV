#!/usr/bin/env python3
"""Tiny ADB-over-Wi-Fi client (no adb binary needed; avoids macOS Local Network blocking).

    tv.py --probe                            (TV_HOST env or optimize/.tv_host)
    tv.py 'pm list packages'                 one shell command
    tv.py --batch < cmds.txt                 one command per line, same connection
    tv.py --pull /sdcard/x.png x.png
    tv.py --push-b64 local.apk /data/local/tmp/x.apk   chunked base64 upload with md5 check
    tv.py --shot out.png                     screenshot from the TV
    tv.py --reboot                           restart the TV (sent once, never retried) and wait until it is back
    tv.py --wait                             wait until the TV answers again (after a reboot), up to 5 min

Built for slow or flaky Wi-Fi: connecting and every command are retried, a dropped connection is
reopened, and an interrupted upload resumes where it stopped.
"""
import base64
import hashlib
import os
import re
import sys
import time
from pathlib import Path

for _stream in (sys.stdout, sys.stderr):   # Windows writes pipes in its own code page (Arabic would crash) and with \r\n (Git Bash keeps the \r)
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace", newline="\n")

try:
    from adb_shell.adb_device import AdbDeviceTcp
    from adb_shell.auth.keygen import keygen
    from adb_shell.auth.sign_pythonrsa import PythonRSASigner
except ModuleNotFoundError:
    sys.exit("!! The Python packages aren't in optimize/venv. Run optimize/setup.sh: it must end with "
             "«✓ Python ADB client», else it says why. Then the same command again.")

HERE = os.path.dirname(os.path.abspath(__file__))
HOST_FILE = os.path.join(HERE, ".tv_host")  # written by find-tv.sh, git-ignored
HOST = os.environ.get("TV_HOST") or (open(HOST_FILE).read().strip() if os.path.exists(HOST_FILE) else "")
if not HOST:
    sys.exit("Don't know your TV's IP. Run optimize/find-tv.sh, or set TV_HOST=192.168.x.y")
PORT = int(os.environ.get("TV_PORT", "5555"))
KEY = os.path.join(HERE, "adbkey", "adbkey")
TRIES = int(os.environ.get("TV_TRIES", "4"))


def signer():
    os.makedirs(os.path.dirname(KEY), exist_ok=True)
    if not os.path.exists(KEY):
        keygen(KEY)
    return PythonRSASigner(open(KEY + ".pub").read(), open(KEY).read())


class TV:
    """One connection, reopened on failure; every call retried with a growing pause."""

    def __init__(self):
        self.dev = None

    def _open(self):
        self.close()
        dev = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=60.0)
        dev.connect(rsa_keys=[signer()], auth_timeout_s=120)
        self.dev = dev

    def close(self):
        if self.dev is not None:
            try:
                self.dev.close()
            except Exception:
                pass
            self.dev = None

    def call(self, fn, *args, **kw):
        for attempt in range(TRIES):
            try:
                if self.dev is None:
                    self._open()
                return getattr(self.dev, fn)(*args, **kw)
            except Exception as e:  # noqa: BLE001  (network errors come in many types)
                self.close()
                if attempt == TRIES - 1:
                    sys.exit(f"!! TV not answering after {TRIES} tries ({type(e).__name__}: {e}). "
                             "Check the TV is on and on Wi-Fi, then run the same command again.")
                wait = 5 * (attempt + 1)
                print(f"   (connection problem: {type(e).__name__}; retrying in {wait}s)", file=sys.stderr)
                time.sleep(wait)

    def shell(self, cmd, timeout=120):
        return self.call("shell", cmd, read_timeout_s=timeout) or ""


ANDROID_ROOT = re.compile(r"/(?:data|sdcard|storage|system|vendor|product|apex|mnt|proc|dev|cache|odm|oem)(?=/|$)")


def tv_path(arg):
    """A path on the TV, as Git Bash on Windows may have rewritten it: it turns an argument like /data/local/tmp/x
    into C:/Program Files/Git/data/local/tmp/x before Python sees it. Cut its own folder back off."""
    if os.name != "nt" or not re.match(r"[A-Za-z]:[\\/]", arg):
        return arg
    s = arg.replace("\\", "/")
    for m in ANDROID_ROOT.finditer(s):
        if is_git_folder(s[:m.start()]):
            return s[m.start():]
    return arg


def is_git_folder(folder):   # Git Bash's own folder is the one with its bash in it
    return os.path.exists(folder + "/usr/bin/bash.exe") or os.path.exists(folder + "/bin/bash.exe")


def push_b64(tv, local, remote, chunk=96_000):   # one argument may not exceed 128 KB on Android
    """Upload in base64 pieces; on a retry, pieces already on the TV are kept (resumes, not restarts)."""
    data = open(local, "rb").read()
    enc = base64.b64encode(data).decode()
    folder = remote.rsplit("/", 1)[0] or "/"
    if tv.shell(f"[ -d '{folder}' ] && echo y").strip() != "y":
        sys.exit(f"!! {folder} is not a folder on the TV, so nothing can be uploaded to {remote}.")
    tmp = remote + ".b64"
    have = tv.shell(f"[ -f {tmp} ] && wc -c < {tmp} || echo 0").strip()
    done = int(have) if have.isdigit() and int(have) % chunk == 0 and int(have) <= len(enc) else 0
    if done:   # a killed run may have left a piece in twice: resume only if what is there is exactly right
        theirs = tv.shell(f"md5sum {tmp}").split()[0] if tv.shell(f"[ -f {tmp} ] && echo y").strip() else ""
        if theirs != hashlib.md5(enc[:done].encode()).hexdigest():
            done = 0
    if done == 0:
        tv.shell(f"rm -f {tmp} {remote}")
    elif done:
        print(f"resuming upload at {done * 100 // len(enc)}%")
    for i in range(done, len(enc), chunk):
        piece, want = enc[i:i + chunk], str(min(i + chunk, len(enc)))
        size = tv.shell(f"echo -n '{piece}' >> {tmp}; wc -c < {tmp}").strip()   # write and check in one call
        for _ in range(5):    # a retried call wrote the piece twice, or not at all: cut back and write it again
            if size == want:
                break
            size = tv.shell(f"head -c {i} {tmp} > {tmp}.x && mv {tmp}.x {tmp}; echo -n '{piece}' >> {tmp}; wc -c < {tmp}").strip()
        if size != want:
            free = tv.shell(f"df -h {folder} | tail -1").strip()
            sys.exit(f"!! upload keeps failing at {i * 100 // len(enc)}% (the TV wrote {size or 'nothing'} of {want}; "
                     f"space there: {free}). Run the same command again.")
        print(f"\r   uploading {Path(local).name}: {int(want) * 100 // len(enc)}%", end="", flush=True)
    print()
    tv.shell(f"[ -f {tmp} ] && base64 -d {tmp} > {remote} && rm -f {tmp}")   # safe to repeat if the reply is lost
    remote_md5 = tv.shell(f"md5sum {remote}").split()[0] if tv.shell(f"[ -f {remote} ] && echo y").strip() else ""
    local_md5 = hashlib.md5(data).hexdigest()
    if remote_md5 != local_md5:
        tv.shell(f"rm -f {remote} {tmp}")
        sys.exit(f"md5 mismatch: {remote_md5} != {local_md5} (run the same command again)")
    print(f"pushed {local} -> {remote} ({len(data)} bytes, md5 ok)")


def wait_for_tv(limit_s=300):
    """After a reboot: try every 10 s until the TV answers and has finished booting."""
    start = time.time()
    while time.time() - start < limit_s:
        try:
            dev = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=15.0)
            dev.connect(rsa_keys=[signer()], auth_timeout_s=30)
            booted = dev.shell("getprop sys.boot_completed", read_timeout_s=15).strip()
            dev.close()
            if booted == "1":
                print(f"TV is back ({int(time.time() - start)} s)")
                return
        except Exception:  # noqa: BLE001
            pass
        time.sleep(10)
    sys.exit("!! TV did not come back within 5 minutes. Check it is on and on Wi-Fi (ADB stays on after a reboot).")


def main():
    a = sys.argv[1:]
    if a == ["--wait"]:
        wait_for_tv()
        return
    if a == ["--reboot"]:   # once: a retried reboot could restart the TV twice
        try:
            dev = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=30.0)
            dev.connect(rsa_keys=[signer()], auth_timeout_s=60)
            dev.shell("reboot", read_timeout_s=10)
        except Exception:  # noqa: BLE001  (the connection drops as the TV goes down)
            pass
        print("restarting the TV…")
        time.sleep(45)
        wait_for_tv()
        return
    tv = TV()
    if not a or a == ["--probe"]:
        for p in ("ro.product.manufacturer", "ro.product.brand", "ro.product.model", "ro.product.device",
                  "ro.build.version.release", "ro.build.version.sdk", "ro.build.display.id"):
            print(f"{p} = {tv.shell(f'getprop {p}').strip()}")
    elif a[0] == "--batch":
        for line in sys.stdin:
            line = line.strip()
            if line and not line.startswith("#"):
                print(f"$ {line}\n{tv.shell(line).rstrip()}")
    elif a[0] == "--pull":
        tv.call("pull", tv_path(a[1]), a[2])
    elif a[0] == "--push-b64":
        push_b64(tv, a[1], tv_path(a[2]))
    elif a[0] == "--shot":
        tv.shell("screencap -p /sdcard/_shot.png")
        tv.call("pull", "/sdcard/_shot.png", a[1])
        tv.shell("rm /sdcard/_shot.png")
        print(f"saved {a[1]}")
    else:
        print(tv.shell(" ".join(tv_path(x) for x in a)).rstrip())
    tv.close()


if __name__ == "__main__":
    main()
