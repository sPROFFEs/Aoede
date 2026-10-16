#!/bin/sh
set -eu

# Provider traffic may use a proxy; local DevTools HTTP/WebSocket traffic must not.
export NO_PROXY="${NO_PROXY:+$NO_PROXY,}localhost,127.0.0.1,::1"
export no_proxy="${no_proxy:+$no_proxy,}localhost,127.0.0.1,::1"

install -d -o downloader -g downloaders -m 2770 /downloads /downloads/jobs
# No in-memory job survives a worker restart, so its old staging directories
# cannot be claimed safely and would otherwise accumulate forever.
find /downloads/jobs -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
install -d -o downloader -g downloaders -m 0750 /home/downloader/.spotiflac
install -d -o downloader -g downloaders -m 0750 /home/downloader/.spotiflac/extensions
install -d -o downloader -g downloaders -m 0750 /home/downloader/.auth-browser
chown -R downloader:downloaders /home/downloader/.auth-browser
# Extensions are image-managed and checksum-pinned. Replace the managed set on
# every start while preserving the rest of SpotiFLAC's persistent state.
find /home/downloader/.spotiflac/extensions -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
cp -a /opt/spotiflac-extensions/. /home/downloader/.spotiflac/extensions/
chown -R downloader:downloaders /home/downloader/.spotiflac

exec gosu downloader "$@"
