from collections.abc import Callable, Collection
from contextvars import ContextVar
from urllib.parse import urlsplit

from starlette.datastructures import MutableHeaders
from starlette.exceptions import HTTPException
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Message, Receive, Scope, Send

SECURITY_HEADERS = {
    "X-Content-Type-Options": "nosniff",
    "X-Frame-Options": "DENY",
    "Referrer-Policy": "strict-origin-when-cross-origin",
    "Permissions-Policy": "camera=(), microphone=(), geolocation=()",
    "Content-Security-Policy": (
        "default-src 'self'; script-src 'self' 'unsafe-inline' https://unpkg.com https://www.youtube.com "
        "https://s.ytimg.com; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com "
        "https://cdnjs.cloudflare.com; font-src 'self' https://fonts.gstatic.com https://cdnjs.cloudflare.com; "
        "img-src 'self' data: blob: https:; media-src 'self' blob: https:; "
        "frame-src 'self' https://www.youtube.com; "
        "connect-src 'self' https:; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"
    ),
}


class RequestContextMiddleware:
    """Apply request language, same-origin validation, and security headers without buffering responses."""

    def __init__(
        self,
        app: ASGIApp,
        *,
        language_context: ContextVar[str],
        supported_languages: Collection[str],
        default_language: str,
        validate_request: Callable[[Request], None],
    ) -> None:
        self.app = app
        self.language_context = language_context
        self.supported_languages = supported_languages
        self.default_language = default_language
        self.validate_request = validate_request

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request = Request(scope, receive=receive)
        language = request.cookies.get("lang")
        if not language:
            accept_lang = (request.headers.get("accept-language") or "").lower()
            if "de" in accept_lang:
                language = "de"
            elif "es" in accept_lang:
                language = "es"
            else:
                language = self.default_language
        if language not in self.supported_languages:
            language = self.default_language
        token = self.language_context.set(language)

        async def send_with_security_headers(message: Message) -> None:
            if message["type"] == "http.response.start":
                headers = MutableHeaders(scope=message)
                # Routing slash redirects must keep the client's scheme/port,
                # even when an HTTPS tunnel reaches the backend over HTTP.
                if 300 <= message["status"] < 400 and "location" in headers:
                    target = urlsplit(headers["location"])
                    if (
                        target.scheme in {"http", "https"}
                        and target.netloc == request.url.netloc
                        and not target.path.startswith("//")
                    ):
                        headers["location"] = target._replace(scheme="", netloc="").geturl()
                for name, value in SECURITY_HEADERS.items():
                    if name not in headers:
                        headers[name] = value
            await send(message)

        try:
            try:
                self.validate_request(request)
            except HTTPException as error:
                response = JSONResponse({"detail": error.detail}, status_code=error.status_code, headers=error.headers)
                await response(scope, receive, send_with_security_headers)
                return
            await self.app(scope, receive, send_with_security_headers)
        finally:
            self.language_context.reset(token)
