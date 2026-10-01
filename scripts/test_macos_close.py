"""Run the real Cocoa close paths in a test-only build on a macOS CI runner."""

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
    window = source / "api/window/window.cpp"
    original = window.read_text()
    # This hook is compiled ONLY after the production binaries have been saved.
    hook = """
    #if defined(__APPLE__)
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        nativeWindow->navigate(std::getenv("AOEDE_TEST_URL"));
    });
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 3 * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        std::string mode = std::getenv("AOEDE_TEST_CLOSE");
        if(mode == "worker") {
            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{ app::exit(); });
        } else if(mode == "quit") {
            ((void (*)(id, SEL, id))objc_msgSend)("NSApp"_cls, "terminate:"_sel, nullptr);
        } else {
            ((void (*)(id, SEL, id))objc_msgSend)((id)windowHandle, "performClose:"_sel, nullptr);
        }
    });
    #endif
"""
    assert original.count("    nativeWindow->run();") == 1
    window.write_text(original.replace("    nativeWindow->run();", hook + "    nativeWindow->run();"))
    try:
        subprocess.run(["cmake", "--build", str(source / "build"), "--parallel", "3"], check=True)
        binary = next((source / "bin").glob("neutralino-mac_*"))
        subprocess.run(["codesign", "--force", "--sign", "-", str(binary)], check=True)
        handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(desktop / "resources"))
        with http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler) as server:
            threading.Thread(target=server.serve_forever, daemon=True).start()
            for mode in ("main", "worker", "quit"):
                with tempfile.TemporaryDirectory() as temporary:
                    app = Path(temporary)
                    shutil.copy2(desktop / "neutralino.config.json", app)
                    shutil.copytree(desktop / "resources", app / "resources")
                    env = dict(
                        os.environ, AOEDE_TEST_CLOSE=mode, AOEDE_TEST_URL=f"http://127.0.0.1:{server.server_port}/"
                    )
                    result = subprocess.run(
                        [str(binary), f"--path={app}", "--load-dir-res"],
                        env=env,
                        capture_output=True,
                        text=True,
                        timeout=20,
                    )
                    assert result.returncode == 0, f"{mode} close failed: {result.returncode}\n{result.stderr}"
                    print(f"PASS: Cocoa {mode} close after external navigation", flush=True)
            server.shutdown()
    finally:
        window.write_text(original)


if __name__ == "__main__":
    main()
