import subprocess
import time

from fastapi.templating import Jinja2Templates


def _compute_static_version() -> str:
    """Return a short git hash, or epoch seconds as fallback.
    Called once at process start — result is stable for the life of the process
    so all CSS/JS links share the same cache-busting token and the browser can
    actually cache the assets between page loads.
    """
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            capture_output=True,
            text=True,
            timeout=3,
        )
        if result.returncode == 0 and result.stdout.strip():
            return result.stdout.strip()
    except Exception:
        pass
    return str(int(time.time()))


from pathlib import Path

# Instancia compartida de plantillas
TEMPLATES_DIR = Path(__file__).resolve().parent / "templates"
if not TEMPLATES_DIR.is_dir():
    TEMPLATES_DIR = Path("templates")

import i18n


def appearance_context(request):
    theme = getattr(request.state, "user_theme", None)
    return {"user_theme": theme if theme in ("classic", "glass", "vitrine") else "classic"}


class AccountTemplates(Jinja2Templates):
    def TemplateResponse(self, request, name, context=None, **kwargs):
        # Native forms use the original authenticated controllers and validation.
        # Serialize only their result, never the ORM user or provider credentials.
        if (
            request.scope.get("method", "GET") == "POST"
            and request.url.path
            in {
                "/user/change-password",
                "/user/update-api-keys",
                "/user/validate-api-keys",
                "/user/upload-cookies",
                "/user/upload-avatar",
            }
            and "application/json" in request.headers.get("accept", "")
        ):
            from fastapi.responses import JSONResponse

            values = context or {}
            error = values.get("error")
            payload = (
                {"error": str(error)[:400]}
                if error
                else {"ok": True, "message": str(values.get("success", "Updated"))[:400]}
            )
            return JSONResponse(
                payload, status_code=400 if error else 200, headers={"Cache-Control": "private, no-store"}
            )
        return super().TemplateResponse(request, name, context, **kwargs)


templates = AccountTemplates(directory=str(TEMPLATES_DIR), context_processors=[appearance_context])
# Expose static asset version as a Jinja2 global so every template can use
# {{ static_v }} without any Python-side boilerplate.
templates.env.globals["static_v"] = _compute_static_version()
templates.env.globals["_"] = lambda key: i18n.get_text(key)
templates.env.globals["current_lang"] = lambda: "en"
# The SPA uses the same request language and English fallback as server-rendered text.
templates.env.globals["get_all_translations"] = lambda: {
    **i18n.translations.get(i18n.DEFAULT_LANG, {}),
    **i18n.translations.get(templates.env.globals["current_lang"](), {}),
}
templates.env.globals["domain"] = "localhost"
