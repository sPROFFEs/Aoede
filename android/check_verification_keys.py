#!/usr/bin/env python3
"""Exercise the production verification input allowlist on local synthetic DOM only."""

import importlib.util
import socket
import sys
import time
from http.server import ThreadingHTTPServer
from pathlib import Path
from threading import Thread

from playwright.sync_api import sync_playwright
from visual_reference import Reference


def check():
    path = Path(__file__).resolve().parents[1] / "Aoede/downloader-worker/auth_browser.py"
    spec = importlib.util.spec_from_file_location("aoede_verification_keys", path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    server = ThreadingHTTPServer(("127.0.0.1", 0), Reference)
    Thread(target=server.serve_forever, daemon=True).start()
    try:
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            debugging_port = listener.getsockname()[1]
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True, args=[f"--remote-debugging-port={debugging_port}"])
            page = browser.new_page()
            page.goto(f"http://127.0.0.1:{server.server_port}/fixture/keyboard")
            manager = module.AuthBrowserManager()
            manager.debugging_port = debugging_port
            manager._session = module.BrowserSession(
                "local_keys_check", "deezer", page.url, time.time(), time.time() + 60
            )

            def key(value):
                manager.native_view("local_keys_check", {"action": "key", "key": value})

            page.locator("#first").focus()
            manager.native_view("local_keys_check", {"action": "text", "text": "hello"})
            key("Space")
            manager.native_view("local_keys_check", {"action": "text", "text": "world"})
            assert page.locator("#first").input_value() == "hello world"
            key("Control+A")
            manager.native_view("local_keys_check", {"action": "text", "text": "replacement"})
            assert page.locator("#first").input_value() == "replacement"
            key("Tab")
            key("Space")
            assert page.locator("#check").is_checked()
            key("Tab")
            key("Shift+Tab")
            assert page.evaluate("document.activeElement.id") == "check"
            key("Space")
            assert not page.locator("#check").is_checked()
            key("Tab")
            key("Tab")
            key("Enter")
            page.wait_for_function("document.querySelector('#submit').dataset.clicked === 'yes'", timeout=2000)
            try:
                key("F12")
                raise AssertionError("Unexpected unbounded browser command")
            except ValueError:
                pass
            browser.close()
    finally:
        server.shutdown()
        server.server_close()
    print("Verified Space, text, checkbox, Tab, Shift+Tab, Ctrl+A and Enter on local Chromium; F12 rejected.")


if __name__ == "__main__":
    check()
