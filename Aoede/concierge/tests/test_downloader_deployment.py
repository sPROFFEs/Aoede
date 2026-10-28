import json
import subprocess
from pathlib import Path

import database
import pytest
import update_service
import yaml
from aoede_config import Settings

PROJECT_ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.parametrize(
    "old_url",
    [
        "https://github.com/sPROFFEs/Navipod",
        "http://github.com/sPROFFEs/Navipod/",
        "https://github.com/sPROFFEs/Navipod.git",
        "ssh://git@github.com/sPROFFEs/Navipod.git",
        "git@github.com:sPROFFEs/Navipod.git",
    ],
)
def test_renamed_official_update_source_migrates(old_url, monkeypatch):
    monkeypatch.setenv("UPDATE_SOURCE_REPO_URL", old_url)
    assert Settings(_env_file=None).UPDATE_SOURCE_REPO_URL == "https://github.com/sPROFFEs/Aoede"


def test_updates_show_current_source_without_reusing_another_repository_check(monkeypatch):
    source = "https://github.com/sPROFFEs/Aoede"
    monkeypatch.setattr(update_service.ops.settings, "UPDATE_SOURCE_REPO_URL", source)
    monkeypatch.setattr(update_service.ops.settings, "UPDATE_SOURCE_BRANCH", "main")
    saved = {
        "source_repo_url": "https://github.com/sPROFFEs/Navipod.git",
        "source_branch": "main",
        "checked_at": "2026-10-14T12:00:00Z",
        "current": {"commit": "1234567", "repo_url": "https://github.com/sPROFFEs/Navipod"},
        "behind_count": 2,
        "update_available": True,
    }
    row = type("SettingsRow", (), {"update_state_json": json.dumps(saved)})()
    monkeypatch.setattr(update_service.ops, "ensure_system_settings_record", lambda _db: row)
    monkeypatch.setattr(update_service, "get_build_info", lambda: {"commit": "7654321", "repo_url": source})
    state = update_service.get_update_state(None)
    assert state["source_repo_url"] == state["current"]["repo_url"] == source
    assert state["checked_at"] == saved["checked_at"]
    assert state["behind_count"] == 2
    assert state["update_available"] is True
    assert json.loads(row.update_state_json) == saved  # Rendering does not rewrite history.

    fork = "https://github.com/example/Navipod"
    assert Settings(_env_file=None, UPDATE_SOURCE_REPO_URL=fork).UPDATE_SOURCE_REPO_URL == fork
    monkeypatch.setattr(update_service.ops.settings, "UPDATE_SOURCE_REPO_URL", fork)
    state = update_service.get_update_state(None)
    assert state["source_repo_url"] == fork
    assert "checked_at" not in state and state["update_available"] is False
    assert update_service.is_update_state_stale(None) is True

    row.update_state_json = json.dumps({**saved, "source_repo_url": source, "source_branch": "develop"})
    monkeypatch.setattr(update_service.ops.settings, "UPDATE_SOURCE_REPO_URL", source)
    assert "checked_at" not in update_service.get_update_state(None)


def test_all_compose_variants_include_isolated_downloader():
    compose_paths = [
        PROJECT_ROOT / "docker-compose.yaml",
        PROJECT_ROOT / "deployment-templates" / "internal" / "docker-compose.yaml",
        PROJECT_ROOT / "deployment-templates" / "domain" / "docker-compose.yaml",
    ]
    for compose_path in compose_paths:
        payload = yaml.safe_load(compose_path.read_text(encoding="utf-8"))
        service = payload["services"]["downloader"]
        assert "ports" not in service
        assert service["security_opt"] == ["no-new-privileges:true"]
        assert service["cap_drop"] == ["ALL"]
        assert service["shm_size"] == "1gb"
        assert service["init"] is True  # Reap orphaned browser/FFmpeg children.
        assert "KILL" in service["cap_add"]  # Init forwards shutdown to the unprivileged worker.
        assert service["pids_limit"] == "${DOWNLOADER_PIDS_LIMIT:-1024}"
        startup_timeout = "AUTH_BROWSER_STARTUP_TIMEOUT=${AUTH_BROWSER_STARTUP_TIMEOUT:-30}"
        assert startup_timeout in service["environment"]
        assert any("download-staging:/downloads" in volume for volume in service["volumes"])
        concierge = payload["services"]["concierge"]
        assert "downloader" in concierge["depends_on"]
        assert concierge["environment"]
        assert startup_timeout in concierge["environment"]
        updater = payload["services"]["updater"]
        assert updater["build"]["context"] == "./concierge"
        assert updater["build"]["dockerfile"] == "Dockerfile.updater"


def test_worker_image_installs_spotiflac_browser_runtime():
    dockerfile = (PROJECT_ROOT / "downloader-worker" / "Dockerfile").read_text(encoding="utf-8")

    assert "chromium" in dockerfile
    assert "xvfb" in dockerfile
    assert "command -v Xvfb" in dockerfile


def test_worker_image_uses_pinned_official_deno_binary():
    dockerfile = (PROJECT_ROOT / "downloader-worker" / "Dockerfile").read_text(encoding="utf-8")

    assert "ARG DENO_VERSION=" in dockerfile
    assert "FROM docker.io/denoland/deno:bin-${DENO_VERSION} AS deno" in dockerfile
    assert "COPY --from=deno /deno /usr/local/bin/deno" in dockerfile
    assert "https://deno.land/install.sh" not in dockerfile


def test_concierge_image_uses_pinned_deno_and_updater_has_minimal_system_runtime():
    concierge = (PROJECT_ROOT / "concierge" / "Dockerfile").read_text(encoding="utf-8")
    updater = (PROJECT_ROOT / "concierge" / "Dockerfile.updater").read_text(encoding="utf-8")

    assert "FROM docker.io/denoland/deno:bin-${DENO_VERSION} AS deno" in concierge
    assert "https://deno.land/install.sh" not in concierge
    assert "--no-install-recommends" in concierge
    assert "--no-install-recommends" in updater
    assert "ffmpeg" not in updater
    assert "nodejs" not in updater
    assert "deno" not in updater.lower()


def test_worker_entrypoint_prepares_persistent_auth_browser_volume():
    entrypoint = (PROJECT_ROOT / "downloader-worker" / "entrypoint.sh").read_text(encoding="utf-8")
    assert "/home/downloader/.auth-browser" in entrypoint
    assert "chown -R downloader:downloaders /home/downloader/.auth-browser" in entrypoint

    # Exercise the actual exports without running ownership/deployment commands.
    exports = "\n".join(line for line in entrypoint.splitlines() if line.startswith("export "))
    result = subprocess.run(
        ["/bin/sh", "-c", exports + '\nprintf "%s\\n%s" "$NO_PROXY" "$no_proxy"'],
        env={"NO_PROXY": "keep.example", "no_proxy": "other.example"},
        capture_output=True,
        text=True,
        check=True,
    )
    assert result.stdout.splitlines() == [
        "keep.example,localhost,127.0.0.1,::1",
        "other.example,localhost,127.0.0.1,::1",
    ]


def test_auth_browser_proxy_supports_cookie_and_legacy_query_handoff():
    nginx = (PROJECT_ROOT / "nginx.conf").read_text(encoding="utf-8")
    authorization = nginx.split("location = /_internal/auth-browser-authorize {", 1)[1].split("}", 1)[0]
    location = nginx.split("location ^~ /admin/auth-browser/ {", 1)[1].split("}", 1)[0]

    assert "proxy_set_header Cookie $http_cookie;" in authorization
    assert "proxy_set_header X-Auth-Browser-Session $auth_browser_session;" in authorization
    assert "proxy_set_header X-Auth-Browser-Token $auth_browser_token;" in authorization
    assert "set $auth_browser_session $arg_session_id;" in location
    assert "set $auth_browser_token $arg_token;" in location
    assert 'add_header X-Frame-Options "SAMEORIGIN" always;' in location
    assert "add_header Content-Security-Policy \"frame-ancestors 'self'\" always;" in location


def test_updater_rebuilds_worker_for_worker_changes_and_keeps_updater_alive():
    selected, deferred = update_service._select_services_for_update(
        ["Aoede/downloader-worker/worker.py", "Aoede/concierge/downloader_worker_client.py"]
    )

    assert selected == ["concierge", "downloader"]
    assert deferred == ["updater"]


def test_regular_frontend_change_does_not_recreate_worker():
    selected, deferred = update_service._select_services_for_update(["Aoede/assets/css/style.css"])

    assert selected == []
    assert deferred == ["updater"]


def test_frontend_change_with_python_tests_does_not_recreate_services():
    selected, deferred = update_service._select_services_for_update(
        [
            "Aoede/assets/css/ui_home.css",
            "Aoede/assets/js/modules/party.js",
            "Aoede/assets/js/modules/views.js",
            "Aoede/concierge/templates/login.html",
            "Aoede/concierge/tests/test_frontend_styles.py",
        ]
    )

    assert selected == []
    assert deferred == ["updater"]


def test_concierge_runtime_python_change_recreates_concierge():
    selected, deferred = update_service._select_services_for_update(["Aoede/concierge/routers/admin.py"])

    assert selected == ["concierge"]
    assert deferred == ["updater"]


def test_compose_change_keeps_tunnel_running():
    selected, deferred = update_service._select_services_for_update(["Aoede/docker-compose.yaml"])

    assert selected == ["concierge", "downloader", "nginx"]
    assert "tunnel" not in selected
    assert deferred == ["updater"]


def test_tunnel_uses_dynamic_network_address():
    payload = yaml.safe_load((PROJECT_ROOT / "docker-compose.yaml").read_text(encoding="utf-8"))

    assert payload["services"]["tunnel"]["networks"] == ["navidrome-net"]


def test_host_bind_compose_uses_inspected_concierge_mount_and_preserves_named_volumes(monkeypatch):
    repo_root = PROJECT_ROOT.parent
    host_repo_root = Path("/srv/aoede")
    host_concierge_root = host_repo_root / "Aoede" / "concierge"

    monkeypatch.setattr(update_service.ops, "REPO_ROOT", repo_root)
    monkeypatch.setattr(update_service.ops, "COMPOSE_PROJECT_ROOT", PROJECT_ROOT)

    def fake_mount_source(destination):
        if destination == repo_root:
            return host_repo_root
        if destination == Path("/app"):
            return host_concierge_root
        return None

    monkeypatch.setattr(update_service.ops, "_get_container_mount_source", fake_mount_source)
    generated = update_service.ops._build_host_bind_compose_file()
    try:
        payload = yaml.safe_load(generated.read_text(encoding="utf-8"))
    finally:
        generated.unlink(missing_ok=True)

    concierge_volumes = payload["services"]["concierge"]["volumes"]
    downloader_volumes = payload["services"]["downloader"]["volumes"]
    assert f"{host_concierge_root.as_posix()}:/app" in concierge_volumes
    assert f"{host_concierge_root.as_posix()}/templates:/app/templates" in concierge_volumes
    assert "downloader-state:/home/downloader/.spotiflac" in downloader_volumes


def test_mount_discovery_falls_back_to_current_container_id_when_configured_name_is_stale(monkeypatch):
    inspect_targets = []
    monkeypatch.setenv("SELF_CONTAINER_NAME", "aoede_updater")
    monkeypatch.setenv("HOSTNAME", "runtime-container-id")

    def fake_run(args, **_kwargs):
        inspect_targets.append(args[-1])
        if args[-1] == "runtime-container-id":
            return subprocess.CompletedProcess(
                args,
                0,
                stdout='[{"Type":"bind","Source":"/srv/aoede","Destination":"/workspace"}]\n',
                stderr="",
            )
        return subprocess.CompletedProcess(args, 1, stdout="", stderr="No such container")

    monkeypatch.setattr(update_service.ops.subprocess, "run", fake_run)

    source = update_service.ops._get_container_mount_source(Path("/workspace"))

    assert source == Path("/srv/aoede")
    assert inspect_targets == ["aoede_updater", "runtime-container-id"]


def test_container_compose_recreate_fails_closed_when_host_mounts_cannot_be_resolved(monkeypatch):
    monkeypatch.setenv("SELF_CONTAINER_NAME", "aoede_updater")
    monkeypatch.setattr(update_service.ops, "_build_host_bind_compose_file", lambda: None)

    with pytest.raises(RuntimeError, match="refusing an unsafe Compose recreate"):
        update_service.ops._run_compose_command(["up", "-d", "concierge"])


def test_stale_compose_container_cleanup_uses_service_label(monkeypatch):
    calls = []

    def fake_docker(args, **_kwargs):
        calls.append(args)
        if args[0] == "ps":
            return subprocess.CompletedProcess(
                args,
                0,
                stdout=(
                    "abc123\taoede-tunnel-1\tExited (1)\ttunnel\ndef456\taoede-concierge-1\tUp 1 minute\tconcierge\n"
                ),
                stderr="",
            )
        return subprocess.CompletedProcess(args, 0, stdout="", stderr="")

    monkeypatch.setattr(update_service.ops, "_run_docker_command", fake_docker)

    removed = update_service.ops.cleanup_stale_recreate_containers(["tunnel", "concierge"])

    assert removed == ["aoede-tunnel-1"]
    assert ["rm", "-f", "abc123"] in calls


def test_compose_update_forces_runtime_recreation_for_bind_mounted_source():
    assert update_service._build_compose_update_args(False, ["concierge"]) == [
        "up",
        "-d",
        "--force-recreate",
        "--no-deps",
        "--remove-orphans",
        "concierge",
    ]
    assert update_service._build_compose_update_args(True, ["concierge", "downloader"]) == [
        "up",
        "-d",
        "--force-recreate",
        "--build",
        "--no-deps",
        "--remove-orphans",
        "concierge",
        "downloader",
    ]


def test_failed_deployment_survives_worker_only_success_and_up_to_date_checks(db_session, monkeypatch):
    def job(status, details):
        record = database.AdminJob(job_type="apply_update", status=status, details_json=json.dumps(details))
        db_session.add(record)
        db_session.commit()
        return record

    job("completed", {"services": ["concierge", "downloader"]})
    job("failed", {"services": ["concierge", "downloader", "updater", "tunnel"], "rebuild_required": True})
    job("completed", {"services": ["downloader"]})
    job("completed", {"message": "Already up to date"})
    job("failed", ["invalid history"])
    current = job("queued", {})
    assert update_service._pending_update_services(db_session, current.id) == (["concierge"], True)

    row = type("SettingsRow", (), {"update_state_json": '{"update_available":false}'})()
    monkeypatch.setattr(update_service.ops, "ensure_system_settings_record", lambda _db: row)
    monkeypatch.setattr(update_service, "get_build_info", lambda: {"commit": "latest"})
    state = update_service.get_update_state(db_session)
    assert state["update_available"] and state["deployment_pending"]
    assert state["pending_services"] == ["concierge"]
    job("completed", {"services": ["concierge"]})
    assert update_service._pending_update_services(db_session) == ([], False)
    assert not update_service.get_update_state(db_session)["update_available"]


def test_retry_deploys_pending_runtime_when_git_is_current(db_session, monkeypatch, tmp_path):
    import backup_service

    failed = database.AdminJob(
        job_type="apply_update",
        status="failed",
        details_json=json.dumps({"services": ["concierge"], "rebuild_required": True}),
    )
    current = database.AdminJob(job_type="apply_update", status="queued", details_json="{}")
    db_session.add_all([failed, current])
    db_session.commit()
    job_id = current.id
    progress = []
    commands = []

    async def check():
        return {"status": "ok", "update_available": False}

    monkeypatch.setattr(database, "SessionLocal", lambda: db_session)
    monkeypatch.setattr(update_service, "_reclaim_zombie_admin_lock", lambda _db: None)
    monkeypatch.setattr(update_service, "acquire_lock", lambda *_: True)
    monkeypatch.setattr(update_service, "release_lock", lambda *_: None)
    monkeypatch.setattr(update_service, "_clear_git_index_lock", lambda: False)
    monkeypatch.setattr(update_service, "update_admin_job_progress", lambda _id, **kwargs: progress.append(kwargs))
    monkeypatch.setattr(update_service, "_get_update_check_payload", check)
    monkeypatch.setattr(update_service, "save_update_state", lambda *_: None)
    monkeypatch.setattr(update_service, "_resolve_target_update_sha", lambda: ("a" * 40, {}))
    monkeypatch.setattr(update_service, "_fetch_target_update_ref", lambda _: {"ok": True})
    monkeypatch.setattr(update_service, "_run_git", lambda args, **_: {"ok": True} if args[0] == "reset" else "")
    monkeypatch.setattr(update_service.ops, "ensure_runtime_dirs", lambda: None)
    monkeypatch.setattr(update_service.ops, "BACKUP_ROOT", tmp_path)
    monkeypatch.setattr(update_service.ops, "apply_schema_migrations", lambda: [])
    monkeypatch.setattr(backup_service, "_build_backup_manifest", lambda *_: {})
    monkeypatch.setattr(backup_service, "_write_backup_zip", lambda path, _: path.write_bytes(b"test-backup"))
    monkeypatch.setattr(backup_service, "_rotate_current_to_previous", lambda _: None)
    monkeypatch.setattr(backup_service, "_upsert_backup_artifact", lambda *_, **__: None)
    monkeypatch.setattr(database.engine, "dispose", lambda: None)
    monkeypatch.setattr(update_service.ops, "cleanup_stale_recreate_containers", lambda _: [])
    monkeypatch.setattr(update_service, "_run_post_update_health_check", lambda **_: {"ok": True})

    def compose(args, **_):
        commands.append(args)
        return subprocess.CompletedProcess(args, 0, stdout="", stderr="")

    monkeypatch.setattr(update_service.ops, "_run_compose_command", compose)
    monkeypatch.setattr(
        update_service.subprocess, "run", lambda args, **_: subprocess.CompletedProcess(args, 0, "", "")
    )
    update_service.run_apply_update_job_from_updater(job_id, "test-admin")
    assert commands == [update_service._build_compose_update_args(True, ["concierge"])]
    assert progress[-1]["status"] == "completed", progress[-1]
    assert progress[-1]["extra"]["after"]["update_available"] is False
    assert any(item.get("extra", {}).get("services") == ["concierge"] for item in progress)


def test_bind_mounted_runtime_changes_do_not_trigger_image_rebuild():
    changed_files = ["Aoede/nginx.conf", "Aoede/concierge/auth_browser.py"]

    assert update_service.ops.should_rebuild_for_changed_files(changed_files) is False
    assert update_service.ops.matched_rebuild_targets(changed_files) == []


def test_worker_image_changes_still_trigger_image_rebuild():
    changed_files = ["Aoede/downloader-worker/Dockerfile", "Aoede/downloader-worker/worker.py"]

    assert update_service.ops.should_rebuild_for_changed_files(changed_files) is True
    assert update_service.ops.matched_rebuild_targets(changed_files) == [
        "downloader-worker/Dockerfile",
        "downloader-worker/worker.py",
    ]


def test_unavailable_worker_does_not_fail_legacy_compatible_update_health(monkeypatch):
    class Response:
        status_code = 200

    def fake_get(url, **_kwargs):
        if url == "http://downloader:8081/health":
            raise OSError("worker is still building")
        return Response()

    monkeypatch.setattr(update_service.httpx, "get", fake_get)

    result = update_service._run_post_update_health_check(downloader_required=False)

    assert result["ok"] is True
    assert result["downloader"]["available"] is False


def test_worker_only_update_health_requires_downloader(monkeypatch):
    class Response:
        status_code = 200

    def fake_get(url, **_kwargs):
        if url == "http://downloader:8081/health":
            raise OSError("worker is down")
        return Response()

    monkeypatch.setattr(update_service, "HEALTH_CHECK_TIMEOUT_SECONDS", 0.01)
    monkeypatch.setattr(update_service.httpx, "get", fake_get)
    monkeypatch.setattr(update_service.time, "sleep", lambda _seconds: None)

    result = update_service._run_post_update_health_check(downloader_required=True)

    assert result["ok"] is False
    assert "worker-only mode" in result["error"]


def test_stale_updater_runtime_is_restarted_once(monkeypatch):
    class Response:
        def raise_for_status(self):
            return None

        def json(self):
            return {"status": "ok"}

    restarted = []
    monkeypatch.setattr(update_service.httpx, "get", lambda *_args, **_kwargs: Response())
    monkeypatch.setattr(update_service, "_restart_updater_container", lambda: restarted.append(True))

    assert update_service.refresh_stale_updater_runtime() is True
    assert restarted == [True]


def test_current_updater_runtime_is_not_rebuilt(monkeypatch):
    class Response:
        def raise_for_status(self):
            return None

        def json(self):
            return {
                "status": "ok",
                "runtime_generation": update_service.UPDATER_RUNTIME_GENERATION,
                "source_current": True,
            }

    monkeypatch.setattr(update_service.httpx, "get", lambda *_args, **_kwargs: Response())
    monkeypatch.setattr(
        update_service,
        "_restart_updater_container",
        lambda: (_ for _ in ()).throw(AssertionError("current updater was rebuilt")),
    )

    assert update_service.refresh_stale_updater_runtime() is False
