"""
Shared HTTP Client with connection pooling.
Use this instead of creating new httpx.AsyncClient() instances.
"""

import httpx
from network_security import PinnedAsyncTransport

# Shared async client with connection pooling
http_client = httpx.AsyncClient(
    timeout=15.0,
    limits=httpx.Limits(
        max_connections=100,  # Max total connections
        max_keepalive_connections=20,  # Keep alive for reuse
    ),
    follow_redirects=True,
)

# For user-controlled public media URLs. Other providers retain their shared
# origin pool; these requests validate and pin DNS at the actual connection.
public_http_client = httpx.AsyncClient(
    transport=PinnedAsyncTransport(), timeout=httpx.Timeout(15.0, read=None), follow_redirects=False, trust_env=False
)


async def close_http_client():
    """Call on app shutdown to clean up connections."""
    await http_client.aclose()
    await public_http_client.aclose()
