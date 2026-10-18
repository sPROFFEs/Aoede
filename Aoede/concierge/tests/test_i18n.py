import json
import re
from contextvars import ContextVar
from html.parser import HTMLParser
from pathlib import Path

import i18n
from shared_templates import templates

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT.parent / "assets"


def test_catalogues_cover_interface_keys_placeholders_and_format_values():
    catalogues = {lang: json.loads((ROOT / "locales" / f"{lang}.json").read_text()) for lang in i18n.SUPPORTED_LANGS}
    english = catalogues[i18n.DEFAULT_LANG]
    for lang, catalogue in catalogues.items():
        assert catalogue.keys() == english.keys(), lang
        for key, value in catalogue.items():
            assert value.strip(), (lang, key)
            assert set(re.findall(r"\{(\w+)\}", value)) == set(re.findall(r"\{(\w+)\}", english[key])), (lang, key)

    for folder, pattern in [(ROOT / "templates", "*.html"), (ASSETS / "js", "*.js")]:
        for path in folder.rglob(pattern):
            source = path.read_text()
            keys = re.findall(r"\bt\(\s*['\"]([^'\"]+)['\"]|\b_\(['\"]([^'\"]+)['\"]", source)
            assert {js or template for js, template in keys} <= english.keys(), path
            # Human-readable placeholders must use the catalogue; a URL example is language-neutral.
            for value in re.findall(r"\bplaceholder\s*=\s*['\"]([^'\"]*)", source):
                assert value.startswith(("${", "{{", "https://", "http://")), (path, value)


class PlaceholderParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.fields = {}

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs and "placeholder" in attrs:
            self.fields[attrs["id"]] = attrs["placeholder"]


def test_spa_receives_request_language_and_safe_fallback_catalogue(monkeypatch):
    catalogues = {lang: json.loads((ROOT / "locales" / f"{lang}.json").read_text()) for lang in i18n.SUPPORTED_LANGS}
    monkeypatch.setattr(i18n, "translations", catalogues)
    language = ContextVar("test_language", default="en")
    monkeypatch.setitem(templates.env.globals, "current_lang", language.get)
    monkeypatch.setitem(templates.env.globals, "_", lambda key: i18n.get_text(key, language.get()))
    context = {
        "username": "Listener",
        "user_theme": "classic",
        "is_admin": False,
        "pool": {"used": 0, "limit": 10, "percent": 0},
    }
    for lang in ["es", "de", "en"]:
        language.set(lang)
        html = templates.get_template("app_shell.html").render(**context)
        catalogue = json.loads(re.search(r"window\.__I18N__ = (.*);", html)[1])
        assert catalogue == catalogues[lang]
        assert f'window.__CURRENT_LANG__ = "{lang}";' in html
        fields = PlaceholderParser()
        fields.feed(html)
        assert fields.fields["sidebar-query"] == catalogue["menu.search"]
        assert fields.fields["topbar-search-input"] == catalogue["header.search_placeholder"]
        assert fields.fields["dl-modal-url"] == catalogue["downloads.modal_placeholder"]

    # A partial translation uses the English entry without mutating either language.
    del catalogues["es"]["library.search_saved"]
    catalogues["es"]["search.placeholder"] = 'Buscar "álbum" </script>'
    language.set("es")
    html = templates.get_template("app_shell.html").render(**context)
    assert 'Buscar "álbum" </script>' not in html
    catalogue = json.loads(re.search(r"window\.__I18N__ = (.*);", html)[1])
    assert catalogue["library.search_saved"] == catalogues["en"]["library.search_saved"]
    assert catalogue["search.placeholder"] == 'Buscar "álbum" </script>'
    assert "library.search_saved" not in catalogues["es"]
