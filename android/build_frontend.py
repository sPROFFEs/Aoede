#!/usr/bin/env python3
"""Bundle the canonical Aoede frontend without importing the server or its database."""

import hashlib
import json
import re
import shutil
from pathlib import Path

from jinja2 import Environment, FileSystemLoader, select_autoescape

PROJECT = Path(__file__).resolve().parent


def build(output=None):
    source = PROJECT.parent / "Aoede"
    if not source.is_dir():
        source = PROJECT / "frontend-source"
    output = Path(output) if output is not None else PROJECT / "app/src/main/assets/web"
    if output.exists():
        shutil.rmtree(output)
    shutil.copytree(source / "assets", output / "assets")
    concierge = source / "concierge"
    templates = Environment(loader=FileSystemLoader(concierge / "templates"), autoescape=select_autoescape())
    english = json.loads((concierge / "locales/en.json").read_text(encoding="utf-8"))
    for lang in ("en", "es", "de"):
        catalogue = {**english, **json.loads((concierge / f"locales/{lang}.json").read_text(encoding="utf-8"))}
        templates.globals.update(
            _=lambda key: catalogue.get(key, key),
            current_lang=lambda: lang,
            get_all_translations=lambda: catalogue,
            static_v="android",
        )
        for role in ("user", "admin", "login"):
            html = templates.get_template("login.html" if role == "login" else "app_shell.html").render(
                username="AOEDE_MOBILE_USERNAME",
                is_admin=role == "admin",
                user_theme="AOEDE_MOBILE_THEME",
                error="AOEDE_MOBILE_LOGIN_ERROR" if role == "login" else None,
                pool={"used": "AOEDE_MOBILE_POOL_USED", "limit": "AOEDE_MOBILE_POOL_LIMIT", "percent": 0},
            )
            # Native Android uses its system font and packaged icons; no startup CDN requests.
            html = re.sub(r'<link\b[^>]*https://fonts\.[^>]*>', '', html)
            html = html.replace("https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css", "/assets/vendor/fontawesome/css/all.min.css")
            html = re.sub(r'<script\b[^>]*src="https://unpkg\.com/[^>]*></script>', '', html)
            html = html.replace('<html lang="en"', f'<html lang="{lang}" data-client="android"', 1)
            html = re.sub(r'(<meta name="viewport" content=")[^"]*', r'\g<1>width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover', html, count=1)
            # Template scripts can mention <head> in comments; only modify the real document head.
            html = html.replace('<head>', '<head><script src="/_aoede/media-session.js"></script>', 1)
            html = html.replace('</head>', '<link rel="stylesheet" href="/_aoede/mobile.css"><script src="/_aoede/runtime.js"></script></head>', 1)
            (output / f"{lang}-{role}.html").write_text(html, encoding="utf-8")
    native_locales = PROJECT / "app/src/main/assets/native/locales"
    native_locales.mkdir(parents=True, exist_ok=True)
    for lang in ("en", "es", "de"):
        shutil.copyfile(concierge / f"locales/{lang}.json", native_locales / f"{lang}.json")
    files = {str(p.relative_to(output)): hashlib.sha256(p.read_bytes()).hexdigest()
             for p in sorted(output.rglob("*")) if p.is_file()}
    (output / "manifest.json").write_text(json.dumps({"protocol": 1, "files": files}, sort_keys=True), encoding="utf-8")
    print(f"Bundled {len(files)} frontend files")


if __name__ == "__main__":
    build()
