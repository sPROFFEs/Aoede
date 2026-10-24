"""Exercise backup selection and integrity in the actual Android publish script."""

import hashlib
import os
import re
import subprocess
from pathlib import Path

import pytest
import yaml


@pytest.mark.parametrize("mode", ["current", "fallback", "missing", "corrupt"])
def test_android_publication_preserves_verified_backups_after_old_releases_are_removed(tmp_path, mode):
    root = Path(__file__).resolve().parents[3]
    workflow = yaml.safe_load((root / ".github/workflows/android.yml").read_text())
    step = next(
        s
        for s in workflow["jobs"]["publish"]["steps"]
        if s.get("name") == "Preserve the original remote-wrapper backup"
    )
    script = step["run"]
    fixtures = tmp_path / "fixtures"
    fixtures.mkdir()
    for name in ["aoede-android-wrapper-1.0.5.apk", "aoede-android-wrapper-1.0.5-source.zip"]:
        payload = ("original-test-bytes:" + name).encode()
        (fixtures / name).write_bytes(payload)
        # Substitute tiny local fixtures; keep the workflow's real checksum command.
        script, count = re.subn(
            r"[0-9a-f]{64}(  wrapper-backup/" + re.escape(name) + r")",
            hashlib.sha256(payload).hexdigest() + r"\1",
            script,
        )
        assert count == 1
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    gh = bin_dir / "gh"
    gh.write_text("""#!/usr/bin/env python3
import os, pathlib, shutil, sys
args = sys.argv[1:]
mode = os.environ['BACKUP_TEST_MODE']
if args[0] == 'api':
    print('' if mode == 'missing' else 'v-archive')
elif args[:2] == ['release', 'download']:
    if args[2] == 'v-target' and mode in {'fallback', 'missing'}:
        sys.exit(1)
    destination = pathlib.Path(args[args.index('--dir') + 1])
    destination.mkdir(exist_ok=True)
    for source in pathlib.Path('fixtures').iterdir():
        shutil.copyfile(source, destination / source.name)
    if mode == 'corrupt':
        (destination / 'aoede-android-wrapper-1.0.5.apk').write_bytes(b'changed-backup')
else:
    sys.exit(2)
""")
    gh.chmod(0o755)
    result = subprocess.run(
        ["bash", "-euo", "pipefail", "-c", script],
        cwd=tmp_path,
        capture_output=True,
        text=True,
        env={
            **os.environ,
            "PATH": f"{bin_dir}:{os.environ['PATH']}",
            "BACKUP_TEST_MODE": mode,
            "AOEDE_RELEASE_TAG": "v-target",
            "AOEDE_REPOSITORY": "example/Aoede",
        },
    )
    assert (result.returncode == 0) == (mode in {"current", "fallback"}), result.stderr
    if result.returncode == 0:
        for source in fixtures.iterdir():
            assert (tmp_path / "wrapper-backup" / source.name).read_bytes() == source.read_bytes()
