"""Compile test-only hooks and exercise actual native close paths after navigation."""

import functools
import http.server
import os
import shutil
import subprocess
import sys
import tempfile
import threading
from pathlib import Path


def main():
    source, desktop = (Path(arg).resolve() for arg in sys.argv[1:])
    if sys.platform == "darwin":
        binary = next((source / "bin").glob("neutralino-mac_*"))
        commands = subprocess.check_output(["otool", "-l", str(binary)], text=True)
        capture = [block for block in commands.split("Load command ") if "ScreenCaptureKit.framework" in block]
        assert capture and all("LC_LOAD_WEAK_DYLIB" in block for block in capture)
    window = source / "api/window/window.cpp"
    original = window.read_text()
    hook = r"""
    std::thread([] {
        std::this_thread::sleep_for(std::chrono::seconds(1));
        nativeWindow->dispatch([] { nativeWindow->navigate(std::getenv("AOEDE_TEST_URL")); });
        std::this_thread::sleep_for(std::chrono::seconds(2));
        std::string mode = std::getenv("AOEDE_TEST_CLOSE");
        if(mode == "worker") {
            std::ofstream(std::getenv("AOEDE_TEST_MARKER")) << mode;
            app::exit();
            return;
        }
        nativeWindow->dispatch([mode] {
            std::ofstream(std::getenv("AOEDE_TEST_MARKER")) << mode;
            #if defined(__APPLE__)
            if(mode == "quit") {
                id app = ((id(*)(id, SEL))objc_msgSend)("NSApplication"_cls, "sharedApplication"_sel);
                ((void (*)(id, SEL, id))objc_msgSend)(app, "terminate:"_sel, nullptr);
            } else {
                ((void (*)(id, SEL, id))objc_msgSend)((id)windowHandle, "performClose:"_sel, nullptr);
            }
            #elif defined(_WIN32)
            SendMessage(windowHandle, WM_CLOSE, 0, 0);
            #else
            gtk_window_close(GTK_WINDOW(windowHandle));
            #endif
        });
    }).detach();
"""
    assert original.count("    nativeWindow->run();") == 1
    window.write_text(
        "#include <fstream>\n#include <thread>\n#include <chrono>\n"
        + original.replace("    nativeWindow->run();", hook + "    nativeWindow->run();")
    )
    try:
        subprocess.run(
            ["cmake", "--build", str(source / "build"), "--config", "Release", "--parallel", "3"], check=True
        )
        binary = next((source / "bin").glob("neutralino-*"))
        if sys.platform == "darwin":
            subprocess.run(["codesign", "--force", "--sign", "-", str(binary)], check=True)
        requests = []

        class Handler(http.server.SimpleHTTPRequestHandler):
            def do_GET(self):
                requests.append(self.path)
                super().do_GET()

        handler = functools.partial(Handler, directory=str(desktop / "resources"))
        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler) as server:
            threading.Thread(target=server.serve_forever, daemon=True).start()
            modes = ("main", "worker", "quit") if sys.platform == "darwin" else ("main", "worker")
            for mode in modes:
                with tempfile.TemporaryDirectory() as temporary:
                    app = Path(temporary)
                    shutil.copy2(desktop / "neutralino.config.json", app)
                    shutil.copytree(desktop / "resources", app / "resources")
                    url = f"http://127.0.0.1:{server.server_port}/?mode={mode}"
                    marker = app / "native-close.txt"
                    env = dict(os.environ, AOEDE_TEST_CLOSE=mode, AOEDE_TEST_URL=url, AOEDE_TEST_MARKER=str(marker))
                    result = subprocess.run(
                        [str(binary), f"--path={app}", "--load-dir-res"],
                        env=env,
                        capture_output=True,
                        text=True,
                        timeout=30,
                    )
                    assert result.returncode == 0, f"{mode} close failed: {result.returncode}\n{result.stderr}"
                    assert marker.read_text() == mode, result.stderr
                    assert f"/?mode={mode}" in requests, "The native webview did not navigate to the remote page"
                    print(f"PASS: {sys.platform} {mode} close after external navigation", flush=True)
            server.shutdown()
    finally:
        window.write_text(original)


if __name__ == "__main__":
    main()
