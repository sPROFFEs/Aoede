"""Pin untrusted outbound URLs to validated addresses without weakening TLS."""

import asyncio
import ipaddress
import socket

import httpx


def is_public_address(value: str) -> bool:
    try:
        address = ipaddress.ip_address(value)
        address = getattr(address, "ipv4_mapped", None) or address
        return address.is_global and not address.is_multicast
    except ValueError:
        return False


def _resolve(url: httpx.URL, allow_private: bool) -> str:
    if url.scheme not in {"http", "https"} or not url.host or url.username or url.password:
        raise httpx.ConnectError("URL is not allowed")
    try:
        addresses = list(
            dict.fromkeys(
                row[4][0]
                for row in socket.getaddrinfo(
                    url.host, url.port or (443 if url.scheme == "https" else 80), type=socket.SOCK_STREAM
                )
            )
        )
    except OSError as error:
        raise httpx.ConnectError("Could not resolve the target") from error
    if not addresses or (not allow_private and not all(map(is_public_address, addresses))):
        raise httpx.ConnectError("Target resolves to a non-public address")
    # ponytail: prefer one IPv4 address; add bounded failover if a provider needs it.
    return min(addresses, key=lambda address: ipaddress.ip_address(address).version)


def _pinned_request(request: httpx.Request, address: str) -> httpx.Request:
    headers = request.headers.copy()
    headers["Host"] = request.url.netloc.decode("ascii")
    return httpx.Request(
        request.method,
        request.url.copy_with(host=address),
        headers=headers,
        stream=request.stream,
        extensions={**request.extensions, "sni_hostname": request.url.raw_host.decode("ascii")},
    )


class PinnedAsyncTransport(httpx.AsyncHTTPTransport):
    def __init__(self, *, allow_private: bool = False):
        self.allow_private = allow_private
        # Pooled origins are IPs after pinning. Never reuse a TLS connection
        # across hostnames sharing an address; SNI must be verified each time.
        super().__init__(trust_env=False, limits=httpx.Limits(max_connections=100, max_keepalive_connections=0))

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        address = await asyncio.to_thread(_resolve, request.url, self.allow_private)
        return await super().handle_async_request(_pinned_request(request, address))


class PinnedTransport(httpx.HTTPTransport):
    def __init__(self, *, allow_private: bool = False):
        self.allow_private = allow_private
        super().__init__(trust_env=False, limits=httpx.Limits(max_connections=100, max_keepalive_connections=0))

    def handle_request(self, request: httpx.Request) -> httpx.Response:
        return super().handle_request(_pinned_request(request, _resolve(request.url, self.allow_private)))


def safe_relay_content_type(value: str | None) -> str | None:
    content_type = (value or "application/octet-stream").split(";", 1)[0].strip().lower()
    if content_type.startswith(("audio/", "video/")) or content_type in {
        "application/octet-stream",
        "application/ogg",
        "application/vnd.apple.mpegurl",
        "application/x-mpegurl",
        "application/json",
        "text/event-stream",
        "text/plain",
        "image/jpeg",
        "image/png",
        "image/webp",
        "image/gif",
        "image/avif",
    }:
        return content_type
    return None
