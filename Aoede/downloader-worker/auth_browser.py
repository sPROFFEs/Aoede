"""Temporary, single-user Chromium session for provider verification.

The manager deliberately owns only process lifecycle. Authorization is kept in
Concierge, which is the only public admin boundary; the worker endpoints are
protected by the existing worker bearer token.
"""

from __future__ import annotations

import asyncio
import base64
import errno
import inspect
import json
import logging
import os
import re
import secrets
import shutil
import signal
import socket
import subprocess
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import ProxyHandler, build_opener

logger = logging.getLogger("aoede.auth_browser")


@dataclass
class BrowserSession:
    session_id: str
    provider: str
    url: str
    started_at: float
    expires_at: float
    status: str = "starting"
    error: str | None = None
    grant: str | None = None

    def serialize(self) -> dict:
        remaining = max(0, int(self.expires_at - time.time()))
        return {
            "status": self.status,
            "provider": self.provider,
            "session_id": self.session_id,
            "started_at": self.started_at,
            "expires_at": self.expires_at,
            "remaining_seconds": remaining,
            "grant_captured": bool(self.grant),
            **({"error": self.error} if self.error else {}),
        }


@dataclass
class BrowserProcess:
    name: str
    process: subprocess.Popen
    log_path: Path


class AuthBrowserManager:
    """Manage one disposable Xvfb/Chromium/VNC/noVNC process tree."""

    def __init__(self) -> None:
        self.enabled = os.getenv("AUTH_BROWSER_ENABLED", "true").strip().lower() not in {"0", "false", "no"}
        self.ttl = max(60, min(1800, int(os.getenv("AUTH_BROWSER_TTL", "600"))))
        self.startup_timeout = max(5, min(120, int(os.getenv("AUTH_BROWSER_STARTUP_TIMEOUT", "30"))))
        self.display = os.getenv("AUTH_BROWSER_DISPLAY", ":99")
        self.width = max(800, min(2560, int(os.getenv("AUTH_BROWSER_WIDTH", "1024"))))
        self.height = max(600, min(1600, int(os.getenv("AUTH_BROWSER_HEIGHT", "768"))))
        self.vnc_port = int(os.getenv("AUTH_BROWSER_VNC_PORT", "5900"))
        self.websocket_port = int(os.getenv("AUTH_BROWSER_WEBSOCKET_PORT", "6080"))
        self.debugging_port = int(os.getenv("AUTH_BROWSER_DEBUGGING_PORT", "9222"))
        self.profile_root = Path(os.getenv("AUTH_BROWSER_PROFILE", "/home/downloader/.auth-browser"))
        self.chromium_no_sandbox = os.getenv("AUTH_BROWSER_CHROMIUM_NO_SANDBOX", "true").strip().lower() not in {
            "0",
            "false",
            "no",
        }
        self._lock = threading.RLock()
        self._session: BrowserSession | None = None
        self._processes: list[BrowserProcess] = []
        self._timer: threading.Timer | None = None
        self._watcher_stop = threading.Event()
        self._watcher_thread: threading.Thread | None = None

    def _validate_url(self, url: str) -> str:
        parsed = urlparse(str(url or "").strip())
        if parsed.scheme not in {"http", "https"} or not parsed.netloc:
            raise ValueError("verification URL must be an HTTP(S) URL")
        host = (parsed.hostname or "").lower()
        if host in {"localhost", "127.0.0.1", "::1"} or host.endswith(".local"):
            raise ValueError("verification URL host is not allowed")
        return parsed.geturl()

    def _command(self, name: str) -> str:
        command = shutil.which(name)
        if not command:
            raise RuntimeError(f"required browser component is unavailable: {name}")
        return command

    def _novnc_root(self) -> str:
        for candidate in ("/usr/share/novnc", "/usr/share/noVNC", "/opt/novnc", "/opt/noVNC"):
            if Path(candidate).is_dir():
                return candidate
        raise RuntimeError("noVNC assets are unavailable in the downloader image")

    def _chromium_command(self, chromium: str, browser_profile: Path, url: str) -> list[str]:
        command = [
            chromium,
            f"--display={self.display}",
            f"--user-data-dir={browser_profile}",
            f"--window-size={self.width},{self.height}",
            "--remote-debugging-address=127.0.0.1",
            f"--remote-debugging-port={self.debugging_port}",
            "--no-first-run",
            "--no-default-browser-check",
            "--disable-sync",
        ]
        # Docker remains the outer sandbox: this container has no new
        # privileges and drops all capabilities. Chromium's namespace/SUID
        # sandbox cannot initialize reliably under that policy.
        if self.chromium_no_sandbox:
            command.append("--no-sandbox")
        command.append(url)
        return command

    def _debugger_json(self, path: str):
        # These are private loopback requests, regardless of the provider proxy.
        with build_opener(ProxyHandler({})).open(
            f"http://127.0.0.1:{self.debugging_port}/{path}", timeout=2
        ) as response:
            return json.load(response)

    def _debugger_websocket_url(self) -> str:
        payload = self._debugger_json("json/version")
        if not isinstance(payload, dict):
            raise RuntimeError("Chromium returned invalid debugging metadata")
        websocket_url = str(payload.get("webSocketDebuggerUrl") or "")
        parsed = urlparse(websocket_url)
        if (
            parsed.scheme != "ws"
            or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
            or parsed.port != self.debugging_port
        ):
            raise RuntimeError("Chromium returned an invalid debugging endpoint")
        return websocket_url

    def native_view(self, session_id: str, action: dict | None = None) -> bytes | None:
        """Bounded CDP screenshot/input on the active local browser, never arbitrary CDP or JavaScript."""
        from websockets.sync.client import connect

        with self._lock:
            session = self._session
            if not session or session.session_id != session_id or time.time() >= session.expires_at:
                raise ValueError("Browser session is unavailable")
            if action is not None:
                kind = action.get("action")
                if kind not in {"click", "text", "key", "scroll"}:
                    raise ValueError("Unsupported browser input")
                if kind in {"click", "scroll"}:
                    x, y = float(action.get("x", 0)), float(action.get("y", 0))
                    if not (0 <= x < self.width and 0 <= y < self.height):
                        raise ValueError("Input outside browser viewport")
                if kind == "text" and (not isinstance(action.get("text"), str) or len(action["text"]) > 2048):
                    raise ValueError("Invalid browser text")
                keys = {
                    "Enter": 13,
                    "Tab": 9,
                    "Backspace": 8,
                    "Escape": 27,
                    "ArrowLeft": 37,
                    "ArrowUp": 38,
                    "ArrowRight": 39,
                    "ArrowDown": 40,
                    "Space": 32,
                    "Delete": 46,
                    "Home": 36,
                    "End": 35,
                    "PageUp": 33,
                    "PageDown": 34,
                    "Shift+Tab": 9,
                    "Control+A": 65,
                }
                if kind == "key" and action.get("key") not in keys:
                    raise ValueError("Unsupported browser key")
            target = self._debugger_websocket_url()
            deadline = time.monotonic() + 4
            # Pin the socket to worker loopback as well as validating Chromium's URL.
            # websockets 14 has no proxy support; explicitly disable it on newer versions.
            options = {"proxy": None} if "proxy" in inspect.signature(connect).parameters else {}
            with socket.create_connection(("127.0.0.1", self.debugging_port), timeout=2) as sock:
                with connect(
                    target,
                    sock=sock,
                    open_timeout=2,
                    close_timeout=1,
                    max_size=2 * 1024 * 1024,
                    compression=None,
                    **options,
                ) as ws:
                    sequence = 0

                    def command(method, params=None, session=None):
                        nonlocal sequence
                        sequence += 1
                        message = {"id": sequence, "method": method, "params": params or {}}
                        if session:
                            message["sessionId"] = session
                        ws.send(json.dumps(message))
                        for _ in range(128):
                            remaining = deadline - time.monotonic()
                            if remaining <= 0:
                                raise RuntimeError("Browser command timed out")
                            reply = json.loads(ws.recv(timeout=remaining))
                            if reply.get("id") == sequence:
                                if "error" in reply:
                                    raise RuntimeError("Browser command unavailable")
                                return reply.get("result", {})
                        raise RuntimeError("Too many browser events")

                    targets = command("Target.getTargets").get("targetInfos", [])
                    pages = [
                        p
                        for p in targets
                        if p.get("type") == "page" and str(p.get("url", "")).startswith(("https://", "http://"))
                    ]
                    if not pages:
                        raise RuntimeError("Browser page is not ready")
                    tab = command("Target.attachToTarget", {"targetId": pages[-1]["targetId"], "flatten": True})[
                        "sessionId"
                    ]
                    if action is None:
                        image = command(
                            "Page.captureScreenshot",
                            {"format": "jpeg", "quality": 65, "captureBeyondViewport": False},
                            tab,
                        ).get("data", "")
                        if not isinstance(image, str) or len(image) > 1400000:
                            raise RuntimeError("Browser frame too large")
                        decoded = base64.b64decode(image, validate=True)
                        if not decoded.startswith(b"\xff\xd8\xff"):
                            raise RuntimeError("Invalid browser frame")
                        return decoded
                    if kind == "text":
                        command("Input.insertText", {"text": action["text"]}, tab)
                    elif kind == "key":
                        key = action["key"]
                        params = {"key": key, "code": key, "windowsVirtualKeyCode": keys[key]}
                        if key == "Space":
                            params.update(key=" ", code="Space")
                        elif key == "Enter":
                            params.update(code="Enter")
                        elif key == "Shift+Tab":
                            params.update(key="Tab", code="Tab", modifiers=8)
                        elif key == "Control+A":
                            params.update(key="a", code="KeyA", modifiers=2)
                        for event in ("keyDown", "keyUp"):
                            command(
                                "Input.dispatchKeyEvent",
                                {
                                    "type": event,
                                    **params,
                                    **(
                                        {"text": " " if key == "Space" else "\r"}
                                        if key in {"Space", "Enter"} and event == "keyDown"
                                        else {}
                                    ),
                                },
                                tab,
                            )
                    elif kind == "click":
                        for event, buttons in (("mouseMoved", 0), ("mousePressed", 1), ("mouseReleased", 0)):
                            command(
                                "Input.dispatchMouseEvent",
                                {
                                    "type": event,
                                    "x": x,
                                    "y": y,
                                    "button": "none" if event == "mouseMoved" else "left",
                                    "buttons": buttons,
                                    "clickCount": 1,
                                },
                                tab,
                            )
                    else:
                        delta = float(action.get("delta", 0))
                        if not -1200 <= delta <= 1200:
                            raise ValueError("Invalid scroll distance")
                        command(
                            "Input.dispatchMouseEvent",
                            {"type": "mouseWheel", "x": x, "y": y, "deltaX": 0, "deltaY": delta},
                            tab,
                        )
                    return None

    def _store_grant(self, session_id: str, grant: str) -> None:
        grant = str(grant or "").strip()
        if len(grant) < 16 or len(grant) > 4096 or any(char.isspace() for char in grant):
            return
        with self._lock:
            if self._session and self._session.session_id == session_id:
                self._session.grant = grant
                self._session.status = "verified"

    async def _watch_grant(self, session: BrowserSession, ready: threading.Event, stop: threading.Event) -> None:
        from pydoll.browser.chromium import Chrome
        from pydoll.protocol.network.events import NetworkEvent

        browser = Chrome(connection_port=self.debugging_port)
        challenge = urlparse(session.url)
        expected_origin = (challenge.scheme, challenge.netloc)
        expected_path = challenge.path.rstrip("/") + "/verify"

        async def on_response(event: dict) -> None:
            try:
                params = event.get("params", {})
                response = params.get("response", {})
                response_url = urlparse(str(response.get("url") or ""))
                if (response_url.scheme, response_url.netloc) != expected_origin or response_url.path != expected_path:
                    return
                body = await tab.get_network_response_body(str(params.get("requestId") or ""))
                payload = json.loads(body)
                if isinstance(payload, dict):
                    self._store_grant(session.session_id, payload.get("grant"))
            except Exception:
                logger.debug("Could not capture provider verification response", exc_info=True)

        try:
            tab = await asyncio.wait_for(browser.connect(self._debugger_websocket_url()), timeout=self.startup_timeout)
            await tab.enable_network_events()
            await tab.on(NetworkEvent.RESPONSE_RECEIVED, on_response)
            ready.set()
            while not stop.is_set():
                await asyncio.sleep(0.1)
        finally:
            try:
                await browser.close()
            except Exception:
                logger.debug("Could not close Chromium grant watcher", exc_info=True)

    def _start_grant_watcher(self, session: BrowserSession, timeout: float) -> None:
        self._watcher_stop = threading.Event()
        stop = self._watcher_stop
        ready = threading.Event()
        startup_error: list[Exception] = []

        def run() -> None:
            try:
                asyncio.run(self._watch_grant(session, ready, stop))
            except Exception as exc:
                startup_error.append(exc)
                ready.set()

        self._watcher_thread = threading.Thread(target=run, name="auth-browser-grant", daemon=True)
        self._watcher_thread.start()
        if not ready.wait(timeout=timeout):
            raise RuntimeError("Chromium grant capture did not become ready within the startup deadline")
        if startup_error:
            raise RuntimeError(f"Chromium grant capture failed: {startup_error[0]}") from startup_error[0]

    def _stop_grant_watcher(self) -> None:
        self._watcher_stop.set()
        watcher = self._watcher_thread
        self._watcher_thread = None
        if watcher and watcher is not threading.current_thread():
            watcher.join(timeout=2)

    @staticmethod
    def _profile_in_use(browser_profile: Path) -> bool:
        expected = f"--user-data-dir={browser_profile.resolve()}"
        for cmdline_path in Path("/proc").glob("[0-9]*/cmdline"):
            try:
                arguments = cmdline_path.read_bytes().decode(errors="replace").split("\0")
            except OSError:
                continue
            if expected in arguments:
                return True
        return False

    def _clear_stale_profile_locks(self, browser_profile: Path) -> None:
        """Remove container-bound Chromium locks only when the profile is idle."""
        if self._profile_in_use(browser_profile):
            raise RuntimeError("Chromium profile is still in use by a running process")
        for name in ("SingletonLock", "SingletonCookie", "SingletonSocket"):
            try:
                (browser_profile / name).unlink(missing_ok=True)
            except OSError as exc:
                raise RuntimeError(f"could not clear stale Chromium profile lock: {name}") from exc

    def _spawn(self, name: str, command: list[str], *, env: dict[str, str] | None = None) -> BrowserProcess:
        log_path = Path("/tmp") / f"aoede-auth-browser-{secrets.token_hex(6)}-{name}.log"
        try:
            with log_path.open("w", encoding="utf-8") as log_stream:
                process = subprocess.Popen(
                    command,
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=log_stream,
                    env=env,
                    start_new_session=True,
                )
        except OSError as exc:
            log_path.unlink(missing_ok=True)
            if exc.errno in {errno.EAGAIN, errno.ENOMEM}:
                raise RuntimeError(self._resource_error(name)) from exc
            raise RuntimeError(f"{name} could not start: {exc.strerror}") from exc
        managed = BrowserProcess(name=name, process=process, log_path=log_path)
        self._processes.append(managed)
        return managed

    @staticmethod
    def _resource_error(name: str) -> str:
        return (
            f"{name} could not start: the downloader has insufficient free processes/threads or memory. "
            "Check DOWNLOADER_PIDS_LIMIT and available memory; recreate the downloader container after updating."
        )

    def _component_error(self, managed: BrowserProcess) -> str:
        try:
            detail = managed.log_path.read_text(encoding="utf-8", errors="replace")[-1200:].strip()
        except OSError:
            detail = ""
        detail = re.sub(r"https?://\S+", "[redacted-url]", detail)
        if "pthread_create" in detail or "Resource temporarily unavailable" in detail:
            logger.warning("Authentication browser resource failure (%s): %s", managed.name, detail)
            return self._resource_error(managed.name)
        return f"{managed.name} exited during startup" + (f": {detail}" if detail else "")

    def _wait_ready(self, managed: BrowserProcess, predicate, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if managed.process.poll() is not None:
                raise RuntimeError(self._component_error(managed))
            if predicate():
                return
            time.sleep(0.1)
        raise RuntimeError(
            f"{managed.name} did not become ready before the {self.startup_timeout} second startup deadline"
        )

    def _chromium_ready(self) -> bool:
        # A bound TCP port alone does not mean DevTools can accept the watcher.
        try:
            self._debugger_websocket_url()
            targets = self._debugger_json("json/list")
            return isinstance(targets, list) and any(
                isinstance(target, dict) and target.get("type") == "page" for target in targets
            )
        except (OSError, ValueError, RuntimeError):
            return False

    @staticmethod
    def _port_ready(port: int) -> bool:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                return True
        except OSError:
            return False

    def _terminate_processes(self) -> None:
        processes = list(reversed(self._processes))
        self._processes.clear()
        for managed in processes:
            process = managed.process
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except OSError:
                if process.poll() is None:
                    process.terminate()
        deadline = time.monotonic() + 5
        for managed in processes:
            process = managed.process
            remaining = max(0, deadline - time.monotonic())
            try:
                process.wait(timeout=remaining)
            except subprocess.TimeoutExpired:
                pass
            # The parent can exit before its Chromium/VNC children. Always
            # clean up its group, including after a failed startup.
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except OSError:
                if process.poll() is None:
                    process.kill()
            process.wait(timeout=2)
        for managed in processes:
            managed.log_path.unlink(missing_ok=True)

    def _expire(self, session_id: str) -> None:
        with self._lock:
            if self._session and self._session.session_id == session_id:
                self._session.status = "expired"
                self._stop_locked()

    def _stop_locked(self) -> None:
        if self._timer:
            self._timer.cancel()
            self._timer = None
        self._stop_grant_watcher()
        self._terminate_processes()
        if self._session:
            self._session.status = "stopping"
        self._session = None

    def cleanup_stale(self) -> None:
        with self._lock:
            self._stop_locked()

    def start(self, provider: str, url: str) -> BrowserSession:
        if not self.enabled:
            raise RuntimeError("authentication browser is disabled")
        provider = str(provider or "").strip().lower()
        if (
            not provider
            or len(provider) > 64
            or any(char not in "abcdefghijklmnopqrstuvwxyz0123456789-_" for char in provider)
        ):
            raise ValueError("provider is malformed")
        url = self._validate_url(url)
        with self._lock:
            current = self.status()
            if current["status"] not in {"idle", "expired", "failed"}:
                raise RuntimeError("auth_browser_already_running")
            self._stop_locked()
            session = BrowserSession(
                session_id=secrets.token_urlsafe(18),
                provider=provider,
                url=url,
                started_at=time.time(),
                expires_at=time.time() + self.ttl,
            )
            self._session = session
            startup_deadline = time.monotonic() + self.startup_timeout

            def remaining() -> float:
                return max(0, startup_deadline - time.monotonic())

            try:
                self._command("Xvfb")
                chromium = self._command("chromium")
                x11vnc = self._command("x11vnc")
                websockify = self._command("websockify")
                novnc = self._novnc_root()
                self.profile_root.mkdir(parents=True, exist_ok=True)
                browser_profile = self.profile_root / "chromium"
                browser_profile.mkdir(parents=True, exist_ok=True)
                self._clear_stale_profile_locks(browser_profile)
                display_env = os.environ.copy()
                display_env["DISPLAY"] = self.display
                display_number = self.display.removeprefix(":").split(".", 1)[0]
                xvfb = self._spawn(
                    "xvfb",
                    [
                        self._command("Xvfb"),
                        self.display,
                        "-screen",
                        "0",
                        f"{self.width}x{self.height}x24",
                        "-ac",
                        "-nolisten",
                        "tcp",
                    ],
                )
                self._wait_ready(xvfb, lambda: Path(f"/tmp/.X11-unix/X{display_number}").exists(), remaining())
                chromium_command = self._chromium_command(chromium, browser_profile, url)
                chromium_process = self._spawn(
                    "chromium",
                    chromium_command,
                    env=display_env,
                )
                self._wait_ready(chromium_process, self._chromium_ready, remaining())
                self._start_grant_watcher(session, remaining())
                vnc = self._spawn(
                    "x11vnc",
                    [
                        x11vnc,
                        "-display",
                        self.display,
                        "-rfbport",
                        str(self.vnc_port),
                        "-localhost",
                        "-nopw",
                        "-forever",
                        "-shared",
                    ],
                    env=display_env,
                )
                self._wait_ready(vnc, lambda: self._port_ready(self.vnc_port), remaining())
                websocket = self._spawn(
                    "websockify",
                    [
                        websockify,
                        f"--web={novnc}",
                        str(self.websocket_port),
                        f"127.0.0.1:{self.vnc_port}",
                    ],
                )
                self._wait_ready(websocket, lambda: self._port_ready(self.websocket_port), remaining())
                session.status = "verified" if session.grant else "ready"
                session.expires_at = time.time() + self.ttl
                self._timer = threading.Timer(self.ttl, self._expire, args=(session.session_id,))
                self._timer.daemon = True
                self._timer.start()
                return session
            except Exception as exc:
                session.status = "failed"
                session.error = str(exc)
                self._stop_locked()
                raise

    def status(self) -> dict:
        with self._lock:
            session = self._session
            if session and time.time() >= session.expires_at:
                session.status = "expired"
                self._stop_locked()
                session = None
            if not session:
                return {"status": "idle", "enabled": self.enabled}
            if not session.grant:
                for managed in self._processes:
                    if managed.process.poll() is not None:
                        session.status = "failed"
                        session.error = self._component_error(managed)
                        result = {"enabled": self.enabled, **session.serialize()}
                        self._stop_locked()
                        return result
            return {"enabled": self.enabled, **session.serialize()}

    def stop(self) -> dict:
        with self._lock:
            self._stop_locked()
            return {"status": "stopped"}

    def captured_grant(self, provider: str) -> str | None:
        with self._lock:
            if not self._session or self._session.provider != provider:
                return None
            return self._session.grant

    def clear_captured_grant(self, provider: str) -> None:
        with self._lock:
            if self._session and self._session.provider == provider:
                self._session.grant = None

    def open_url(self, session_id: str, url: str) -> dict:
        url = self._validate_url(url)
        with self._lock:
            if not self._session or self._session.session_id != session_id:
                raise RuntimeError("auth browser session is not active")
            # Chromium's remote debugging is intentionally not enabled. Open a
            # new tab through xdotool only when available; otherwise the caller
            # must start a new session with the desired verification URL.
            xdotool = shutil.which("xdotool")
            if not xdotool:
                raise RuntimeError("browser URL cannot be changed after startup")
            env = os.environ.copy()
            env["DISPLAY"] = self.display
            subprocess.run(
                [
                    xdotool,
                    "search",
                    "--onlyvisible",
                    "--class",
                    "Chromium",
                    "windowactivate",
                    "--sync",
                    "key",
                    "ctrl+l",
                    "type",
                    url,
                    "key",
                    "Return",
                ],
                env=env,
                check=True,
                timeout=5,
            )
            self._session.url = url
            return self._session.serialize()


manager = AuthBrowserManager()
