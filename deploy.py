#!/usr/bin/env python3
"""AIPilot deploy: [1/2] build? [2/2] upload to site? Linux may offer headless tar.gz."""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import importlib.util
import json
import os
import platform
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tarfile
import time
import zipfile
from datetime import datetime, timezone, timedelta
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parent
DIST = ROOT / "dist"
BUILD_LIBS = ROOT / "build" / "libs"

HOST = os.environ.get("DEPLOY_HOST", "192.168.1.54")
USER = os.environ.get("DEPLOY_USER", "l151")
PASSWORD = os.environ.get("DEPLOY_PASSWORD", "")
REMOTE_DIR = os.environ.get("DEPLOY_REMOTE_DIR", "/var/www/html/downloads")
DEPLOY_SITE_HTML = os.environ.get("DEPLOY_SITE_HTML", "/var/www/html")
DEPLOY_SITE_MODULE = os.environ.get("DEPLOY_SITE_MODULE", "").strip().lower() in ("1", "true", "yes")
SITE_MODULE_SRC = Path(os.environ.get(
    "DEPLOY_SITE_MODULE_SRC",
    str(ROOT.parent / "server151" / "html" / "modules" / "aipilot"),
))

# Gradle 8.11 needs JDK 11–21; project targets Java 21.
_JDK_MIN = 17
_JDK_MAX = 21

_ECHO_SUDO_RE = re.compile(r"echo\s+(?:'[^']*'|\"[^\"]*\"|\S+)\s*\|\s*sudo\s+-S")

AIPILOT_EXE = "AIPilot.exe"
AIPILOT_JAR = "aipilot.jar"
DIST_APP = ROOT / "dist" / "AIPilot"
UNINSTALL_KEY = r"Software\Microsoft\Windows\CurrentVersion\Uninstall\AIPilot"

UPLOAD_ARTIFACT_NAMES = [
    "aipilot.jar",
    "aipilot.jar.sha256",
    "AIPilot-windows.zip",
    "AIPilot-windows.zip.sha256",
    "AIPilot-Setup.exe",
    "AIPilot-Setup.exe.sha256",
    "aipilot-headless.tar.gz",
    "aipilot-headless.tar.gz.sha256",
    "aipilot-linux.tar.gz",
    "aipilot-linux.tar.gz.sha256",
    "build-aipilot.json",
]


def _configure_stdio_utf8() -> None:
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
            sys.stderr.reconfigure(encoding="utf-8")
        except Exception:
            pass


def _redact(text: str) -> str:
    return _ECHO_SUDO_RE.sub("sudo -S", text)


def ask(prompt: str, default: bool) -> bool:
    hint = " [Y/n, д/н] " if default else " [y/N, д/н] "
    try:
        ans = input(f"{prompt}{hint}").strip().lower()
    except EOFError:
        ans = ""
    if not ans:
        return default
    return ans in ("y", "yes", "д", "да")


def _java_exe(home: Path) -> Path:
    name = "java.exe" if os.name == "nt" else "java"
    return home / "bin" / name


def _is_jdk_home(home: Path) -> bool:
    return home.is_dir() and _java_exe(home).is_file()


def _probe_java_major(home: Path) -> int | None:
    java = _java_exe(home)
    if not java.is_file():
        return None
    try:
        proc = subprocess.run(
            [str(java), "-version"],
            capture_output=True,
            text=True,
            timeout=15,
            check=False,
        )
        text = (proc.stderr or "") + (proc.stdout or "")
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r'version "(\d+)(?:\.(\d+))?', text)
    if not m:
        return None
    major = int(m.group(1))
    if major == 1 and m.group(2):
        major = int(m.group(2))
    return major


def _jdk_in_range(home: Path) -> bool:
    if not _is_jdk_home(home):
        return False
    major = _probe_java_major(home)
    return major is not None and _JDK_MIN <= major <= _JDK_MAX


def _candidate_jdk_homes() -> list[Path]:
    homes: list[Path] = []
    preferred = [
        ROOT / ".tmp-jdk21" / "jdk-21.0.12+8",
        Path(r"E:\projects\platform1\untitled\.tmp-jdk21\jdk-21.0.12+8"),
        Path(r"E:\projects\platform1\platforma151\.tmp-jdk21\jdk-21.0.12+8"),
        ROOT.parent / "platform1" / "untitled" / ".tmp-jdk21" / "jdk-21.0.12+8",
    ]
    homes.extend(preferred)

    for tmp_root in (
        ROOT / ".tmp-jdk21",
        Path(r"E:\projects\platform1\untitled\.tmp-jdk21"),
        Path(r"E:\projects\platform1\platforma151\.tmp-jdk21"),
    ):
        if tmp_root.is_dir():
            for child in sorted(tmp_root.iterdir(), reverse=True):
                homes.append(child)

    env_home = os.environ.get("JAVA_HOME", "").strip().strip('"')
    if env_home:
        homes.append(Path(env_home))

    if os.name == "nt":
        pf = Path(os.environ.get("ProgramFiles", r"C:\Program Files"))
        pf86 = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)"))
        for base in (
            pf / "Eclipse Adoptium",
            pf / "Java",
            pf / "Microsoft",
            pf / "Amazon Corretto",
            pf / "Temurin",
            pf86 / "Eclipse Adoptium",
            pf86 / "Java",
        ):
            if not base.is_dir():
                continue
            try:
                names = list(base.iterdir())
            except OSError:
                continue
            for child in sorted(names, reverse=True):
                lower = child.name.lower()
                if "jdk" in lower or "jre" in lower:
                    homes.append(child)
    else:
        for base in (Path("/usr/lib/jvm"), Path("/Library/Java/JavaVirtualMachines")):
            if not base.is_dir():
                continue
            try:
                names = list(base.iterdir())
            except OSError:
                continue
            for child in sorted(names, reverse=True):
                mac = child / "Contents" / "Home"
                homes.append(mac if mac.is_dir() else child)

    seen: set[str] = set()
    out: list[Path] = []
    for h in homes:
        try:
            key = str(h.resolve()).casefold()
        except OSError:
            key = str(h).casefold()
        if key in seen:
            continue
        seen.add(key)
        out.append(h)
    return out


def resolve_java_home() -> Path:
    for home in _candidate_jdk_homes():
        if _jdk_in_range(home):
            return home.resolve()
    raise SystemExit(
        "No JDK 17–21 found for Gradle. Install Temurin 21 under "
        f"{ROOT / '.tmp-jdk21' / 'jdk-21.0.12+8'} "
        "(or set JAVA_HOME to JDK 17–21). Default PATH java is often Java 8."
    )


def gradle_env(java_home: Path) -> dict[str, str]:
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    bin_dir = str(java_home / "bin")
    env["PATH"] = bin_dir + os.pathsep + env.get("PATH", "")
    return env


def run(cmd: list[str], env: dict[str, str] | None = None) -> None:
    print("+", " ".join(cmd))
    subprocess.check_call(cmd, cwd=ROOT, env=env)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def gradle(*tasks: str) -> None:
    java_home = resolve_java_home()
    major = _probe_java_major(java_home)
    print(f"Using JDK {major}: JAVA_HOME={java_home}")
    env = gradle_env(java_home)
    if platform.system() == "Windows":
        run(["gradlew.bat", *tasks], env=env)
    else:
        run(["./gradlew", *tasks], env=env)


def write_build_json(primary: Path, version: str) -> Path:
    sha = sha256_file(primary)
    payload = {
        "id": "aipilot",
        "hash": sha[:8],
        "sha256": sha,
        "updated_at": datetime.now(timezone(timedelta(hours=3))).strftime("%Y-%m-%dT%H:%M:%S+03:00"),
        "version": version,
    }
    dest = DIST / "build-aipilot.json"
    dest.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Wrote", dest)
    return dest


def app_version() -> str:
    gradle = ROOT / "build.gradle.kts"
    if gradle.is_file():
        m = re.search(r'version\s*=\s*"([^"]+)"', gradle.read_text(encoding="utf-8"))
        if m:
            return m.group(1)
    version_txt = ROOT / "src" / "main" / "resources" / "version.txt"
    if version_txt.is_file():
        v = version_txt.read_text(encoding="utf-8").strip()
        if v:
            return v
    return "0.1.1"


def zip_windows_app_image() -> Path:
    app_dir = DIST / "AIPilot"
    exe = app_dir / "AIPilot.exe"
    if not exe.is_file():
        raise SystemExit(f"Windows app-image missing: {exe}")
    zip_path = DIST / "AIPilot-windows.zip"
    if zip_path.exists():
        zip_path.unlink()
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in app_dir.rglob("*"):
            if path.is_file():
                zf.write(path, Path("AIPilot") / path.relative_to(app_dir))
    (DIST / "AIPilot-windows.zip.sha256").write_text(
        sha256_file(zip_path) + "  AIPilot-windows.zip\n", encoding="utf-8"
    )
    print("Windows package", zip_path)
    return zip_path


def build_linux_desktop_tarball() -> Path | None:
    app_bin = DIST / "AIPilot" / "bin" / "AIPilot"
    if not app_bin.is_file():
        print("Skip aipilot-linux.tar.gz (no dist/AIPilot/bin/AIPilot)")
        return None
    subprocess.check_call([sys.executable, str(ROOT / "tools" / "build_linux_package.py")], cwd=ROOT)
    out = DIST / "aipilot-linux.tar.gz"
    if not out.is_file():
        return None
    sha = DIST / "aipilot-linux.tar.gz.sha256"
    if sha.is_file():
        print("Linux desktop package", out)
    return out


def build_all() -> list[Path]:
    DIST.mkdir(parents=True, exist_ok=True)
    is_win = platform.system() == "Windows"
    tasks = ["clean", "test", "jar", "packageApp"]
    if is_win:
        tasks.append("packageInstaller")
    else:
        tasks.append("packageLinuxApp")
    gradle(*tasks)
    jar = BUILD_LIBS / "aipilot.jar"
    if not jar.is_file():
        raise SystemExit("JAR not found: build/libs/aipilot.jar")
    dest_jar = DIST / "aipilot.jar"
    shutil.copy2(jar, dest_jar)
    (DIST / "aipilot.jar.sha256").write_text(sha256_file(dest_jar) + "  aipilot.jar\n", encoding="utf-8")
    print("Built", dest_jar)

    artifacts = [dest_jar, DIST / "aipilot.jar.sha256"]
    if (DIST / "AIPilot" / "AIPilot.exe").is_file():
        artifacts.append(zip_windows_app_image())
        artifacts.append(DIST / "AIPilot-windows.zip.sha256")
        setup = DIST / "AIPilot-Setup.exe"
        if setup.is_file():
            artifacts.append(setup)
            setup_sha = DIST / "AIPilot-Setup.exe.sha256"
            if setup_sha.is_file():
                artifacts.append(setup_sha)
            else:
                (DIST / "AIPilot-Setup.exe.sha256").write_text(
                    sha256_file(setup) + "  AIPilot-Setup.exe\n", encoding="utf-8"
                )
                artifacts.append(DIST / "AIPilot-Setup.exe.sha256")
        elif platform.system() == "Windows":
            print("WARN: AIPilot-Setup.exe not found (packageInstaller skipped or failed)")
    elif platform.system() == "Windows":
        raise SystemExit("packageApp did not produce dist/AIPilot/AIPilot.exe")
    else:
        linux_tar = build_linux_desktop_tarball()
        if linux_tar:
            artifacts.append(linux_tar)
            linux_sha = DIST / "aipilot-linux.tar.gz.sha256"
            if linux_sha.is_file():
                artifacts.append(linux_sha)
        elif (DIST / "AIPilot" / "bin" / "AIPilot").is_file():
            print("WARN: Linux app-image exists but aipilot-linux.tar.gz build failed")
        else:
            print("Skip Linux desktop tar (build on Linux with jpackage for full package)")

    artifacts.append(build_headless_tarball(dest_jar))
    tgz_sha = DIST / "aipilot-headless.tar.gz.sha256"
    if tgz_sha.is_file():
        artifacts.append(tgz_sha)
    artifacts.append(write_build_json(dest_jar, app_version()))
    write_dist_hash_sidecars()
    return artifacts


def build_headless_tarball(jar: Path) -> Path:
    staging = DIST / "aipilot-headless"
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    shutil.copy2(jar, staging / "aipilot.jar")
    script = ROOT / "scripts" / "run-headless.sh"
    shutil.copy2(script, staging / "run-headless.sh")
    # normalize LF for Linux
    text = (staging / "run-headless.sh").read_text(encoding="utf-8").replace("\r\n", "\n")
    (staging / "run-headless.sh").write_text(text, encoding="utf-8")
    readme = staging / "README-HEADLESS.md"
    readme.write_text(
        "# AIPilot headless (Ubuntu)\n\n"
        "```bash\nsudo apt install -y xvfb openjdk-21-jre\n"
        "chmod +x run-headless.sh\n./run-headless.sh\n```\n",
        encoding="utf-8",
    )
    tar_path = DIST / "aipilot-headless.tar.gz"
    with tarfile.open(tar_path, "w:gz") as tar:
        def _filter(ti: tarfile.TarInfo) -> tarfile.TarInfo:
            if ti.name.endswith("run-headless.sh"):
                ti.mode = 0o755
            return ti

        tar.add(staging, arcname="aipilot-headless", filter=_filter)
    (DIST / "aipilot-headless.tar.gz.sha256").write_text(
        sha256_file(tar_path) + "  aipilot-headless.tar.gz\n", encoding="utf-8"
    )
    print("Headless package", tar_path)
    return tar_path


def _site_repo() -> Path:
    return Path(os.environ.get("DEPLOY_SITE_ROOT", str(ROOT.parent / "server151")))


def _userscripts():
    site = _site_repo()
    path = site / "tools" / "aipilot_userscripts.py"
    if not path.is_file():
        raise SystemExit(f"Canonical userscripts helper missing: {path}")
    spec = importlib.util.spec_from_file_location("aipilot_userscripts", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"cannot import {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod, site


def _deploy_password() -> str:
    env = os.environ.get("DEPLOY_PASSWORD", "")
    if env:
        return env
    if PASSWORD:
        return PASSWORD
    site = _site_repo() / "deploy.py"
    if not site.is_file():
        return ""
    text = site.read_text(encoding="utf-8")
    match = re.search(r'DEPLOY_PASSWORD",\s*"([^"]*)"', text)
    return match.group(1) if match else ""


def upload_site_module() -> None:
    """Upload server151 aipilot PHP module when DEPLOY_SITE_MODULE=1."""
    if not DEPLOY_SITE_MODULE:
        return
    if not PASSWORD:
        raise SystemExit("DEPLOY_PASSWORD env var is required for site module upload")
    if not SITE_MODULE_SRC.is_dir():
        raise SystemExit(f"Site module source not found: {SITE_MODULE_SRC}")
    remote_base = f"{DEPLOY_SITE_HTML.rstrip('/')}/modules/aipilot"
    files: list[tuple[Path, str]] = []
    for path in SITE_MODULE_SRC.rglob("*"):
        if path.is_file():
            rel = path.relative_to(SITE_MODULE_SRC).as_posix()
            files.append((path, rel))
    if not files:
        raise SystemExit(f"No files in site module: {SITE_MODULE_SRC}")
    print(f"Site module upload: {len(files)} files from {SITE_MODULE_SRC} -> {remote_base}")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        ssh.connect(HOST, username=USER, password=PASSWORD, timeout=20)
    except Exception as e:
        raise SystemExit(f"SSH failed for host={HOST}: {e}") from e
    sudo = f"echo {PASSWORD!r} | sudo -S"
    try:
        _run_remote(ssh, f"{sudo} bash -lc 'mkdir -p {shlex.quote(remote_base)}'")
        sftp = ssh.open_sftp()
        staged: list[tuple[str, str]] = []
        try:
            for local, rel in files:
                remote = f"/tmp/aipilot_mod_{rel.replace('/', '_')}"
                print(f"SFTP module {rel} ({local.stat().st_size} bytes)")
                sftp.put(str(local), remote)
                staged.append((remote, rel))
        finally:
            sftp.close()
        copies = " && ".join(
            f"cp -f {shlex.quote(remote)} {shlex.quote(remote_base + '/' + rel)}"
            for remote, rel in staged
        )
        cleanup = " ".join(shlex.quote(remote) for remote, _ in staged)
        _run_remote(
            ssh,
            f"{sudo} bash -lc '{copies} && chown -R www-data:www-data {shlex.quote(remote_base)} "
            f"&& rm -f {cleanup} && echo SITE_MODULE_OK'",
        )
        print(f"Site module uploaded to {remote_base}")
    finally:
        ssh.close()


def upload(paths: list[Path]) -> None:
    """Upload via SFTP to /tmp, then sudo cp into www downloads (l151 has no write there)."""
    password = _deploy_password()
    if not password:
        raise SystemExit("DEPLOY_PASSWORD env var is required for upload")
    files = [(p, p.name) for p in paths if p.exists()]
    if not files:
        raise SystemExit("Nothing to upload")

    print("Upload configuration:")
    print(f"  host={HOST} user={USER} remote={REMOTE_DIR}")
    print(f"  artifacts ({len(files)}):")
    for local, name in files:
        print(f"    - {name} ({local.stat().st_size} bytes)")
    if DEPLOY_SITE_MODULE:
        print(f"  site module: {SITE_MODULE_SRC} -> {DEPLOY_SITE_HTML}/modules/aipilot")
    ssh = paramiko.SSHClient()
    ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    try:
        ssh.connect(HOST, username=USER, password=password, timeout=20)
    except Exception as e:
        raise SystemExit(f"SSH failed for host={HOST}: {e}") from e

    sudo = f"echo {password!r} | sudo -S"
    try:
        _run_remote(ssh, f"{sudo} bash -lc 'mkdir -p {shlex.quote(REMOTE_DIR)}'")

        staged: list[tuple[str, str]] = []
        sftp = ssh.open_sftp()
        try:
            for local, name in files:
                remote = f"/tmp/aipilot_deploy_{name}"
                size = local.stat().st_size
                print(f"SFTP put {local} -> {remote} ({size} bytes)")
                sftp.put(str(local), remote)
                staged.append((remote, name))
        finally:
            sftp.close()

        copies = " && ".join(
            f"cp -f {shlex.quote(remote)} {REMOTE_DIR}/{shlex.quote(name)}"
            for remote, name in staged
        )
        cleanup = " ".join(shlex.quote(remote) for remote, _ in staged)
        chmod_names = " ".join(f"{REMOTE_DIR}/{shlex.quote(name)}" for _, name in files)
        names = " ".join(name for _, name in files)
        # Parent site docroot (downloads live under /var/www/html/downloads)
        site_root = REMOTE_DIR.rstrip("/").rsplit("/", 1)[0] or "/var/www/html"
        cache_dir = f"{site_root}/storage/cache"
        _run_remote(
            ssh,
            f"{sudo} bash -lc '"
            f"{copies} "
            f"&& chown www-data:www-data {chmod_names} "
            f"&& chmod 644 {chmod_names} "
            f"&& mkdir -p {shlex.quote(cache_dir + '/pages')} "
            f"&& echo $(date +%s) > {shlex.quote(cache_dir + '/generation')} "
            f"&& chown -R www-data:www-data {shlex.quote(cache_dir)} "
            f"&& rm -f {cleanup} "
            f"&& ls -la {REMOTE_DIR}/aipilot* {REMOTE_DIR}/AIPilot* {REMOTE_DIR}/build-aipilot.json 2>/dev/null; "
            f"echo UPLOAD_OK files=[{names}]'",
        )
        print(f"Uploaded to http://{HOST}/downloads/")
        js, site = _userscripts()
        js.sync_java_mirror(site)
        js.upload_userscripts(ssh, sudo, DEPLOY_SITE_HTML, _run_remote, js.canonical_dir(site))
        upload_site_module()
    finally:
        ssh.close()


def _run_remote(ssh: paramiko.SSHClient, cmd: str, check: bool = True) -> str:
    shown = _redact(cmd)
    print(">", shown[:220] + ("..." if len(shown) > 220 else ""))
    _, stdout, stderr = ssh.exec_command(cmd, get_pty=True)
    out = stdout.read().decode("utf-8", "replace")
    err = stderr.read().decode("utf-8", "replace")
    code = stdout.channel.recv_exit_status()
    text = _redact((out or "") + (err or ""))
    if text.strip():
        print(text[-4000:])
    if check and code != 0:
        raise SystemExit(f"Remote command failed ({code}): {shown[:120]}")
    return text


def _default_install_dir() -> Path:
    local = os.environ.get("LOCALAPPDATA", "")
    return Path(local) / "Programs" / "AIPilot" if local else Path()


def _registry_install_dir() -> Path | None:
    if os.name != "nt":
        return None
    try:
        import winreg
    except ImportError:
        return None
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, UNINSTALL_KEY) as key:
            loc, _typ = winreg.QueryValueEx(key, "InstallLocation")
    except OSError:
        return None
    if not isinstance(loc, str) or not loc.strip():
        return None
    loc = loc.strip().strip('"')
    exe = Path(loc) / AIPILOT_EXE
    return Path(loc) if exe.is_file() else None


def find_local_install_dir() -> Path | None:
    if os.name != "nt":
        return None
    reg = _registry_install_dir()
    if reg:
        return reg
    fb = _default_install_dir()
    return fb if (fb / AIPILOT_EXE).is_file() else None


def _aipilot_running() -> bool:
    if os.name != "nt":
        return False
    try:
        proc = subprocess.run(
            ["tasklist", "/FI", f"IMAGENAME eq {AIPILOT_EXE}", "/FO", "CSV", "/NH"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="ignore",
            timeout=10,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    out = (proc.stdout or "") + (proc.stderr or "")
    return AIPILOT_EXE.lower() in out.lower()


def _clear_readonly(path: str | Path) -> None:
    p = str(path)
    if not os.path.exists(p):
        return
    try:
        os.chmod(p, stat.S_IWRITE | stat.S_IREAD)
    except OSError:
        pass
    if os.name == "nt":
        try:
            ctypes.windll.kernel32.SetFileAttributesW(ctypes.c_wchar_p(p), 0x80)
        except Exception:
            pass


def _file_in_use(path: Path) -> bool:
    if not path.is_file():
        return False
    _clear_readonly(path)
    try:
        fd = os.open(str(path), os.O_RDWR)
        os.close(fd)
        return False
    except OSError:
        return True


def _pids_in_dir(install_dir: Path) -> list[int]:
    if os.name != "nt" or not install_dir:
        return []
    prefix = os.path.abspath(str(install_dir)).rstrip("\\/") + "\\"
    ps_path = prefix.replace("'", "''")
    script = (
        f"$p = '{ps_path}'; "
        "Get-CimInstance Win32_Process | Where-Object { "
        "$_.ExecutablePath -and $_.ExecutablePath.StartsWith($p, "
        "[System.StringComparison]::OrdinalIgnoreCase) "
        "} | ForEach-Object { $_.ProcessId }"
    )
    try:
        proc = subprocess.run(
            ["powershell", "-NoProfile", "-Command", script],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="ignore",
            timeout=15,
        )
    except (OSError, subprocess.TimeoutExpired):
        return []
    pids: list[int] = []
    for line in (proc.stdout or "").splitlines():
        s = line.strip()
        if s.isdigit():
            pids.append(int(s))
    return pids


def _taskkill_im(image: str) -> None:
    subprocess.run(
        ["taskkill", "/F", "/IM", image, "/T"],
        capture_output=True,
        text=True,
        timeout=20,
    )


def _taskkill_pid(pid: int) -> None:
    subprocess.run(
        ["taskkill", "/F", "/PID", str(pid), "/T"],
        capture_output=True,
        text=True,
        timeout=20,
    )


def _stop_aipilot(install_dir: Path | None = None) -> None:
    for _round in range(4):
        _taskkill_im(AIPILOT_EXE)
        for pid in _pids_in_dir(install_dir) if install_dir else []:
            _taskkill_pid(pid)
        deadline = time.time() + 8
        exe = install_dir / AIPILOT_EXE if install_dir else None
        while time.time() < deadline:
            running = _aipilot_running()
            locked = bool(exe) and _file_in_use(exe)
            if not running and not locked:
                return
            time.sleep(0.3)
        time.sleep(0.5)


def _start_aipilot(install_dir: Path) -> None:
    exe = install_dir / AIPILOT_EXE
    if os.name == "nt":
        os.startfile(str(exe))  # type: ignore[attr-defined]
        return
    subprocess.Popen(
        [str(exe)],
        cwd=str(install_dir),
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        stdin=subprocess.DEVNULL,
        start_new_session=True,
    )


def _rename_aside(path: Path) -> bool:
    if not path.exists():
        return True
    _clear_readonly(path)
    for i in range(12):
        candidate = Path(str(path) + (".old" if i == 0 else f".old.{i}"))
        try:
            if candidate.exists():
                _clear_readonly(candidate)
                candidate.unlink()
        except OSError:
            pass
        try:
            os.replace(path, candidate)
            return True
        except OSError:
            time.sleep(0.15 * (i + 1))
    return False


def _cleanup_deploy_tmp(dst: Path) -> None:
    tmp = Path(str(dst) + ".deploy-tmp")
    if tmp.is_file():
        try:
            _clear_readonly(tmp)
            tmp.unlink()
        except OSError:
            pass


def _copy_file_replace(src: Path, dst: Path, *, install_dir: Path | None = None) -> None:
    last: Exception | None = None
    is_main_exe = dst.name.lower() == AIPILOT_EXE.lower()
    for attempt in range(12):
        _cleanup_deploy_tmp(dst)
        try:
            if dst.is_file():
                _clear_readonly(dst)
                if not _rename_aside(dst):
                    if is_main_exe and install_dir:
                        _stop_aipilot(install_dir)
                        _clear_readonly(dst)
                        if not _rename_aside(dst):
                            raise PermissionError(
                                f"locked: {dst} (закройте AIPilot и повторите)"
                            )
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dst)
            _clear_readonly(dst)
            return
        except OSError as e:
            last = e
            if is_main_exe and install_dir and attempt in (0, 2, 5, 8):
                _stop_aipilot(install_dir)
            time.sleep(0.25 * (attempt + 1))
    tmp = Path(str(dst) + ".deploy-tmp")
    try:
        if is_main_exe and install_dir:
            _stop_aipilot(install_dir)
        if dst.is_file():
            _clear_readonly(dst)
        _rename_aside(dst)
        shutil.copy2(src, tmp)
        _clear_readonly(tmp)
        os.replace(tmp, dst)
        _clear_readonly(dst)
        return
    except OSError as e:
        last = e
        _cleanup_deploy_tmp(dst)
    raise last or OSError(f"cannot copy {src} -> {dst}")


def _hash_from_build_json() -> str:
    p = DIST / "build-aipilot.json"
    if not p.is_file():
        return ""
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
        return str(data.get("hash") or "").strip()
    except Exception:
        return ""


def write_install_hashes(dest: Path) -> None:
    h = _hash_from_build_json()
    if not h:
        exe = dest / AIPILOT_EXE
        if not exe.is_file():
            exe = dest / "bin" / AIPILOT_LINUX_BIN
        if exe.is_file():
            h = sha256_file(exe)[:8]
    if not h:
        return
    for folder in (dest, dest / "app"):
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "build-hash.txt").write_text(h + "\n", encoding="utf-8")
    json_src = DIST / "build-aipilot.json"
    if json_src.is_file():
        shutil.copy2(json_src, dest / "build-aipilot.json")
    if os.name == "nt" and os.environ.get("APPDATA"):
        cache_dir = Path(os.environ["APPDATA"]) / "AIPilot"
    else:
        cache_dir = Path.home() / ".config" / "aipilot"
    try:
        cache_dir.mkdir(parents=True, exist_ok=True)
        (cache_dir / "local-build-hash.txt").write_text(h + "\n", encoding="utf-8")
        if json_src.is_file():
            shutil.copy2(json_src, cache_dir / "build-aipilot.json")
    except OSError:
        pass
    print(f"Записан build-hash {h} → {dest}")


def write_dist_hash_sidecars() -> None:
    h = _hash_from_build_json()
    if not h:
        return
    app_dir = DIST / "AIPilot"
    if app_dir.is_dir():
        write_install_hashes(app_dir)


def _copy_tree_replace(src: Path, dest: Path) -> None:
    for root, _dirs, files in os.walk(src):
        rel = os.path.relpath(root, str(src))
        dest_root = dest if rel == "." else dest / rel
        dest_root.mkdir(parents=True, exist_ok=True)
        for name in files:
            _copy_file_replace(Path(root) / name, dest_root / name, install_dir=dest)


def overlay_local_install() -> None:
    src = DIST_APP
    src_exe = src / AIPILOT_EXE
    if not src_exe.is_file():
        print(f"Локальная установка не обновлена: нет {src_exe}")
        return
    dest = find_local_install_dir()
    if not dest:
        fb = _default_install_dir()
        print(f"Локальная установка AIPilot не найдена — пропуск (ожидался {fb}).")
        return
    try:
        if os.path.samefile(src, dest):
            print("Каталог установки совпадает с dist — overlay пропущен.")
            return
    except OSError:
        pass
    dest_exe = dest / AIPILOT_EXE
    _clear_readonly(dest_exe)
    print("Останавливаю AIPilot.exe…")
    _stop_aipilot(dest)
    _clear_readonly(dest_exe)
    if _file_in_use(dest_exe) or _aipilot_running():
        raise PermissionError(
            f"Не удалось разблокировать {dest_exe}. "
            "Закройте AIPilot (трей → Выход) и повторите deploy."
        )
    print(f"Обновляю установку → {dest}")
    _copy_tree_replace(src, dest)
    write_install_hashes(dest)
    print("Локальная установка обновлена.")


AIPILOT_LINUX_BIN = "AIPilot"


def _linux_install_dir() -> Path:
    return Path.home() / ".local" / "share" / "AIPilot"


def _linux_running(install_dir: Path) -> bool:
    bin_path = install_dir / "bin" / AIPILOT_LINUX_BIN
    try:
        proc = subprocess.run(
            ["pgrep", "-f", str(bin_path)],
            capture_output=True,
            text=True,
            timeout=5,
        )
        return proc.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def _stop_linux(install_dir: Path) -> None:
    bin_path = install_dir / "bin" / AIPILOT_LINUX_BIN
    subprocess.run(["pkill", "-f", str(bin_path)], capture_output=True, timeout=10)
    time.sleep(0.5)


def _start_linux(install_dir: Path) -> None:
    bin_path = install_dir / "bin" / AIPILOT_LINUX_BIN
    if bin_path.is_file():
        env = os.environ.copy()
        env.setdefault("GDK_BACKEND", "x11")
        subprocess.Popen(
            [str(bin_path)],
            cwd=str(install_dir),
            env=env,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            stdin=subprocess.DEVNULL,
            start_new_session=True,
        )


def overlay_linux_install() -> None:
    src = DIST_APP
    src_bin = src / "bin" / AIPILOT_LINUX_BIN
    if not src_bin.is_file():
        print(f"Локальная установка Linux не обновлена: нет {src_bin}")
        return
    dest = _linux_install_dir()
    try:
        if dest.resolve() == src.resolve():
            print("Каталог установки совпадает с dist — overlay пропущен.")
            return
    except OSError:
        pass
    print("Останавливаю AIPilot…")
    _stop_linux(dest)
    print(f"Обновляю установку → {dest}")
    for sub in ("bin", "lib", "runtime", "app"):
        s = src / sub
        d = dest / sub
        if s.is_dir():
            if d.exists():
                shutil.rmtree(d)
            shutil.copytree(s, d)
        elif s.is_file():
            d.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(s, d)
    for name in ("AIPilot", "aipilot.ico", "version.txt", "build-hash.txt"):
        s = src / name
        if s.is_file():
            shutil.copy2(s, dest / name)
    write_install_hashes(dest)
    print("Локальная установка обновлена.")


def launch_local_install() -> None:
    is_win = platform.system() == "Windows"
    if is_win:
        dest = find_local_install_dir()
        if dest and (dest / AIPILOT_EXE).is_file():
            print(f"Запускаю AIPilot → {dest / AIPILOT_EXE}")
            _start_aipilot(dest)
            return
        fb = _default_install_dir()
        if fb.is_dir() and (fb / AIPILOT_EXE).is_file():
            print(f"Запускаю AIPilot → {fb / AIPILOT_EXE}")
            _start_aipilot(fb)
            return
        print("Локальная установка AIPilot не найдена — запуск пропущен.")
        return
    if platform.system() == "Linux":
        dest = _linux_install_dir()
        bin_path = dest / "bin" / AIPILOT_LINUX_BIN
        if bin_path.is_file():
            print(f"Запускаю AIPilot → {bin_path}")
            _start_linux(dest)
            return
        print("Локальная установка AIPilot не найдена — запуск пропущен.")


def existing_artifacts() -> list[Path]:
    return [DIST / n for n in UPLOAD_ARTIFACT_NAMES if (DIST / n).exists()]


def main() -> int:
    _configure_stdio_utf8()
    ap = argparse.ArgumentParser(description="AIPilot deploy")
    ap.add_argument("--no-local-install", action="store_true", help="Skip overlay to %LOCALAPPDATA%\\Programs\\AIPilot")
    ap.add_argument(
        "--userscripts-only",
        action="store_true",
        help="Sync and upload Violentmonkey JS to the site (/ai/configs copy buttons)",
    )
    args = ap.parse_args()

    print("AIPilot deploy")
    js, site = _userscripts()
    js_ver = js.verify_canonical(js.canonical_dir(site))
    js.sync_java_mirror(site)
    print(f"AIPilot userscripts {js_ver} (canonical {js.canonical_dir(site)})")

    if args.userscripts_only:
        password = _deploy_password()
        if not password:
            raise SystemExit("DEPLOY_PASSWORD is required for userscript upload")
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        try:
            ssh.connect(HOST, username=USER, password=password, timeout=20)
        except Exception as e:
            raise SystemExit(f"SSH failed for host={HOST}: {e}") from e
        sudo = f"echo {password!r} | sudo -S"
        try:
            js.upload_userscripts(ssh, sudo, DEPLOY_SITE_HTML, _run_remote, js.canonical_dir(site))
        finally:
            ssh.close()
        print("Done.")
        return 0

    do_build = ask("[1/2] Собрать?", True)
    is_win = platform.system() == "Windows"
    is_linux = platform.system() == "Linux"
    do_local = (is_win or is_linux) and not args.no_local_install
    do_launch = False
    if do_local:
        do_launch = ask("[1b] Запустить AIPilot после обновления?", True)
    do_upload = ask("[2/2] Загрузить на сайт?", False)

    artifacts: list[Path] = []
    if do_build:
        if is_win:
            if DIST_APP.is_dir() or (DIST_APP / AIPILOT_EXE).is_file():
                print("Останавливаю AIPilot.exe в dist/AIPilot перед сборкой…")
                _stop_aipilot(DIST_APP)
                debug_log = DIST_APP / "debug.log"
                if debug_log.is_file():
                    _clear_readonly(debug_log)
                    try:
                        debug_log.unlink()
                    except OSError:
                        pass
            install_early = find_local_install_dir()
            if install_early:
                print("Останавливаю AIPilot.exe (локальная установка) перед сборкой…")
                _stop_aipilot(install_early)
        elif is_linux and _linux_install_dir().is_dir():
            print("Останавливаю AIPilot перед сборкой…")
            _stop_linux(_linux_install_dir())
        artifacts = build_all()
        if do_local:
            if is_win:
                overlay_local_install()
            elif is_linux:
                overlay_linux_install()
        elif args.no_local_install:
            print("Обновление локальной установки: пропущено (--no-local-install).")
    if do_upload:
        if not artifacts:
            artifacts = existing_artifacts()
        if not artifacts:
            print("Nothing to upload", file=sys.stderr)
            return 1
        upload(artifacts)
    elif ask("Обновить JS Violentmonkey на сайте (/ai/configs)?", True):
        password = _deploy_password()
        if not password:
            raise SystemExit("DEPLOY_PASSWORD is required for userscript upload")
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        try:
            ssh.connect(HOST, username=USER, password=password, timeout=20)
        except Exception as e:
            raise SystemExit(f"SSH failed for host={HOST}: {e}") from e
        sudo = f"echo {password!r} | sudo -S"
        try:
            js.upload_userscripts(ssh, sudo, DEPLOY_SITE_HTML, _run_remote, js.canonical_dir(site))
        finally:
            ssh.close()
    elif DEPLOY_SITE_MODULE and PASSWORD:
        upload_site_module()
    if do_launch:
        launch_local_install()
    print("Done.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
