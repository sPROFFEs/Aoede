import asyncio
import errno
import importlib.util
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from types import ModuleType, SimpleNamespace

import pytest

SPEC = importlib.util.spec_from_file_location("aoede_auth_browser", Path(__file__).with_name("auth_browser.py"))
module = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules["aoede_auth_browser"] = module
SPEC.loader.exec_module(module)


def test_auth_browser_starts_idle_and_reports_enabled():
    manager = module.AuthBrowserManager()
    payload = manager.status()
    assert payload["status"] == "idle"
    assert "enabled" in payload


def test_auth_browser_rejects_local_verification_targets():
    manager = module.AuthBrowserManager()
    with pytest.raises(ValueError):
        manager._validate_url("http://127.0.0.1:8080/verification")


def test_auth_browser_rejects_non_http_urls():
    manager = module.AuthBrowserManager()
    with pytest.raises(ValueError):
        manager._validate_url("file:///tmp/provider.html")


def test_chromium_uses_container_safe_real_browser_defaults(tmp_path):
    manager = module.AuthBrowserManager()
    command = manager._chromium_command("/usr/bin/chromium", tmp_path, "https://provider.example/verify")
    assert "--no-sandbox" in command
    assert "--disable-gpu" not in command
    assert "--disable-dev-shm-usage" not in command
    assert "--disable-extensions" not in command
    assert "--window-size=1024,768" in command
    assert "--remote-debugging-address=127.0.0.1" in command
    assert "--remote-debugging-port=9222" in command


def test_auth_browser_keeps_captured_grant_private():
    manager = module.AuthBrowserManager()
    session = module.BrowserSession(
        session_id="browser-session",
        provider="qobuz",
        url="https://api.zarz.moe/v2/challenge?id=example",
        started_at=0,
        expires_at=9999999999,
        status="ready",
    )
    manager._session = session

    manager._store_grant(session.session_id, "grant-value-that-is-long-enough")

    assert manager.captured_grant("qobuz") == "grant-value-that-is-long-enough"
    assert session.serialize()["grant_captured"] is True
    assert "grant" not in session.serialize()
    manager.clear_captured_grant("qobuz")
    assert manager.captured_grant("qobuz") is None


def test_auth_browser_removes_stale_container_profile_locks(tmp_path, monkeypatch):
    manager = module.AuthBrowserManager()
    profile = tmp_path / "chromium"
    profile.mkdir()
    for name in ("SingletonLock", "SingletonCookie", "SingletonSocket"):
        (profile / name).symlink_to(f"stale-{name}")
    monkeypatch.setattr(manager, "_profile_in_use", lambda _profile: False)

    manager._clear_stale_profile_locks(profile)

    assert not any((profile / name).exists() or (profile / name).is_symlink() for name in manager_profile_locks())


def test_auth_browser_keeps_profile_locks_when_chromium_is_running(tmp_path, monkeypatch):
    manager = module.AuthBrowserManager()
    profile = tmp_path / "chromium"
    profile.mkdir()
    lock = profile / "SingletonLock"
    lock.symlink_to("live-lock")
    monkeypatch.setattr(manager, "_profile_in_use", lambda _profile: True)

    with pytest.raises(RuntimeError, match="profile is still in use"):
        manager._clear_stale_profile_locks(profile)

    assert lock.is_symlink()


def manager_profile_locks():
    return ("SingletonLock", "SingletonCookie", "SingletonSocket")


def test_component_startup_error_names_process_and_redacts_urls(tmp_path):
    log_path = tmp_path / "chromium.log"
    log_path.write_text("failed while opening https://provider.example/secret?id=abc", encoding="utf-8")
    managed = module.BrowserProcess(name="chromium", process=SimpleNamespace(), log_path=log_path)
    error = module.AuthBrowserManager()._component_error(managed)
    assert error.startswith("chromium exited during startup")
    assert "provider.example" not in error
    assert "[redacted-url]" in error


def test_browser_resource_failure_has_actionable_error_and_private_diagnostics(tmp_path, caplog):
    log_path = tmp_path / "chromium.log"
    log_path.write_text(
        "pthread_create: Resource temporarily unavailable (11) https://provider.example/private?token=example",
        encoding="utf-8",
    )
    managed = module.BrowserProcess("chromium", SimpleNamespace(), log_path)
    error = module.AuthBrowserManager()._component_error(managed)
    assert "DOWNLOADER_PIDS_LIMIT" in error and "memory" in error
    assert "pthread_create" not in error  # Raw Chromium diagnostics belong in worker logs.
    assert "pthread_create" in caplog.text and "provider.example" not in caplog.text


def test_failed_spawn_closes_log_and_reports_resource_error(monkeypatch):
    manager = module.AuthBrowserManager()
    streams = []

    def fail_spawn(_command, **kwargs):
        streams.append(kwargs["stderr"])
        raise OSError(errno.EAGAIN, "Resource temporarily unavailable")

    monkeypatch.setattr(module.subprocess, "Popen", fail_spawn)
    with pytest.raises(RuntimeError, match="DOWNLOADER_PIDS_LIMIT"):
        manager._spawn("chromium", ["chromium", "https://provider.example/private"])
    assert streams[0].closed and not Path(streams[0].name).exists()
    assert manager._processes == []


def test_shutdown_kills_descendants_even_when_parent_already_exited(tmp_path, monkeypatch):
    manager = module.AuthBrowserManager()
    signals = []
    monkeypatch.setattr(module.os, "killpg", lambda pid, sig: signals.append((pid, sig)))
    log = tmp_path / "chromium.log"
    log.touch()
    process = SimpleNamespace(pid=1234, poll=lambda: 1, wait=lambda **_kwargs: 1)
    manager._processes.append(module.BrowserProcess("chromium", process, log))
    manager._terminate_processes()
    assert signals == [(1234, module.signal.SIGTERM), (1234, module.signal.SIGKILL)]
    assert not log.exists() and manager._processes == []


@pytest.mark.parametrize("verified", [False, True])
def test_dead_browser_is_released_without_losing_a_captured_grant(tmp_path, monkeypatch, verified):
    manager = module.AuthBrowserManager()
    manager._session = module.BrowserSession("session-id", "qobuz", "https://provider.example", 0, 9999999999)
    if verified:
        manager._store_grant("session-id", "captured-private-grant")
    log = tmp_path / "chromium.log"
    log.write_text("browser stopped", encoding="utf-8")
    manager._processes.append(module.BrowserProcess("chromium", SimpleNamespace(poll=lambda: 1), log))
    cleaned = []
    monkeypatch.setattr(manager, "_terminate_processes", lambda: cleaned.append(True))
    payload = manager.status()
    assert payload["status"] == ("verified" if verified else "failed")
    assert "captured-private-grant" not in json.dumps(payload)
    if verified:
        assert manager.captured_grant("qobuz") == "captured-private-grant" and not cleaned
    else:
        assert cleaned and manager.status()["status"] == "idle"


def test_chromium_readiness_requires_a_tab_and_tolerates_transient_responses(monkeypatch):
    manager = module.AuthBrowserManager()
    monkeypatch.setattr(manager, "_debugger_websocket_url", lambda: "ws://127.0.0.1:9222/devtools/browser/test")
    for payload, expected in [
        (b"not JSON yet", False),
        (b"[]", False),
        (b"{}", False),
        (b"[null]", False),
        (b'[{"type":"page"}]', True),
    ]:
        monkeypatch.setattr(manager, "_debugger_json", lambda _path: json.loads(payload))
        assert manager._chromium_ready() is expected


def test_devtools_readiness_bypasses_environment_proxy(monkeypatch):
    manager = module.AuthBrowserManager()
    monkeypatch.setenv("http_proxy", "http://127.0.0.1:1")
    monkeypatch.setenv("HTTP_PROXY", "http://127.0.0.1:1")
    monkeypatch.delenv("NO_PROXY", raising=False)
    monkeypatch.delenv("no_proxy", raising=False)

    class DevTools(BaseHTTPRequestHandler):
        def do_GET(self):
            payload = (
                {"webSocketDebuggerUrl": f"ws://127.0.0.1:{manager.debugging_port}/devtools/browser/test"}
                if self.path == "/json/version"
                else [{"type": "page"}]
            )
            self.send_response(200)
            self.end_headers()
            self.wfile.write(json.dumps(payload).encode())

        def log_message(self, *_args):
            pass

    with HTTPServer(("127.0.0.1", 0), DevTools) as server:
        manager.debugging_port = server.server_port
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            assert manager._chromium_ready()
        finally:
            server.shutdown()
            thread.join(timeout=2)


def test_failed_watcher_connection_is_closed_before_startup_reports_failure(monkeypatch):
    manager = module.AuthBrowserManager()
    closed = []

    class Chrome:
        def __init__(self, **_kwargs):
            pass

        async def connect(self, _url):
            raise RuntimeError("CDP connection failed")

        async def close(self):
            await asyncio.sleep(0.05)
            closed.append(True)

    chromium = ModuleType("pydoll.browser.chromium")
    chromium.Chrome = Chrome
    events = ModuleType("pydoll.protocol.network.events")
    events.NetworkEvent = SimpleNamespace(RESPONSE_RECEIVED="response")
    monkeypatch.setitem(sys.modules, chromium.__name__, chromium)
    monkeypatch.setitem(sys.modules, events.__name__, events)
    monkeypatch.setattr(manager, "_debugger_websocket_url", lambda: "ws://127.0.0.1:9222/devtools/browser/test")
    session = module.BrowserSession("session-id", "qobuz", "https://provider.example", 0, 9999999999)
    try:
        with pytest.raises(RuntimeError, match="CDP connection failed"):
            manager._start_grant_watcher(session, 1)
        assert closed == [True]
    finally:
        manager._stop_grant_watcher()


def test_first_start_can_take_more_than_five_seconds_without_relaunching(tmp_path, monkeypatch):
    monkeypatch.setenv("AUTH_BROWSER_PROFILE", str(tmp_path))
    monkeypatch.setenv("AUTH_BROWSER_STARTUP_TIMEOUT", "30")
    manager = module.AuthBrowserManager()
    clock = [0.0]
    monkeypatch.setattr(module.time, "monotonic", lambda: clock[0])
    monkeypatch.setattr(module.time, "sleep", lambda seconds: clock.__setitem__(0, clock[0] + seconds))
    monkeypatch.setattr(manager, "_command", lambda name: name)
    monkeypatch.setattr(manager, "_novnc_root", lambda: "/usr/share/novnc")
    monkeypatch.setattr(manager, "_clear_stale_profile_locks", lambda _path: None)
    monkeypatch.setattr(manager, "_start_grant_watcher", lambda _session, _timeout: None)
    monkeypatch.setattr(manager, "_terminate_processes", lambda: manager._processes.clear())
    launches = []

    def spawn(name, _command, **_kwargs):
        launches.append(name)
        managed = module.BrowserProcess(name, SimpleNamespace(poll=lambda: None), tmp_path / f"{name}.log")
        manager._processes.append(managed)
        return managed

    original_wait = manager._wait_ready

    def wait(managed, _predicate, timeout):
        original_wait(managed, lambda: managed.name != "chromium" or clock[0] >= 10, timeout)

    monkeypatch.setattr(manager, "_spawn", spawn)
    monkeypatch.setattr(manager, "_wait_ready", wait)
    try:
        session = manager.start("qobuz", "https://provider.example/verification")
        assert session.status == "ready" and clock[0] >= 10
        assert launches == ["xvfb", "chromium", "x11vnc", "websockify"]
        assert session.serialize()["remaining_seconds"] >= manager.ttl - 1
    finally:
        manager.stop()


def test_native_browser_only_drives_the_current_session_and_never_exposes_arbitrary_cdp(monkeypatch):
    import base64
    import time
    from unittest.mock import MagicMock

    import websockets.sync.client

    manager = module.AuthBrowserManager()
    manager._session = module.BrowserSession(
        "native_session_a", "deezer", "https://example.test", time.time(), time.time() + 60
    )
    commands = []
    response = {}
    socket = MagicMock()

    def send(raw):
        request = json.loads(raw)
        commands.append(request)
        method = request["method"]
        result = {}
        if method == "Target.getTargets":
            result = {"targetInfos": [{"type": "page", "targetId": "page-a", "url": "https://example.test"}]}
        elif method == "Target.attachToTarget":
            result = {"sessionId": "attached-a"}
        elif method == "Page.captureScreenshot":
            result = {"data": base64.b64encode(b"\xff\xd8\xffimage").decode()}
        response.clear()
        response.update(id=request["id"], result=result)

    socket.send.side_effect = send
    socket.recv.side_effect = lambda timeout: json.dumps(response)
    connector = MagicMock()
    connector.return_value.__enter__.return_value = socket
    monkeypatch.setattr(websockets.sync.client, "connect", connector)
    monkeypatch.setattr(module.socket, "create_connection", MagicMock())
    monkeypatch.setattr(manager, "_debugger_websocket_url", lambda: "ws://127.0.0.1:9222/devtools/browser/native")
    with pytest.raises(ValueError):
        manager.native_view("native_session_b")
    assert not commands
    assert manager.native_view("native_session_a") == b"\xff\xd8\xffimage"
    manager.native_view("native_session_a", {"action": "text", "text": "synthetic text"})
    assert commands[-1]["method"] == "Input.insertText"
    assert commands[-1]["params"] == {"text": "synthetic text"}
    for payload in (
        {"action": "Runtime.evaluate"},
        {"action": "key", "key": "F12"},
        {"action": "click", "x": 99999},
        {"action": "text", "text": "x" * 2049},
    ):
        with pytest.raises(ValueError):
            manager.native_view("native_session_a", payload)
    assert all(command["method"] != "Runtime.evaluate" for command in commands)
    manager._session.expires_at = time.time() - 1
    with pytest.raises(ValueError):
        manager.native_view("native_session_a")
