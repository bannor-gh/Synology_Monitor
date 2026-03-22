# Backlog

## Open

_(nothing open — all tasks complete)_

## Done

- **Repo created and seeded** ✅ Done 2026-03-19 — `Synology_Monitor` repo created on GitHub, all files committed from design doc: `synology_monitor.py`, `app.py`, `Dockerfile`, `requirements.txt`, `synology_hubitat_driver.groovy`, `.github/workflows/deploy.yml`, `docs/architecture.md`.

- **Color status dots on dashboard tile** ✅ Done 2026-03-19 — `nasSummary` renders emoji status dots (🟢🟡🔴) per metric (CPU, RAM, each volume). Thresholds are configurable driver preferences; defaults are CPU 50/80%, RAM 70/85%, Disk 70/85%.

- **Register self-hosted runner** ✅ Done 2026-03-21 — `github-runner-synology` container running on NAS via `myoung34/github-runner:latest`, registered with label `synology-monitor`. Updated 2026-03-21 to use `ACCESS_TOKEN` (GitHub PAT) instead of `RUNNER_TOKEN` — runner now auto-registers on NAS restart without manual token renewal.

- **Create NAS working directory** ✅ Done 2026-03-21 — `/volume1/Synology Monitor/` created via File Station.

- **First deploy** ✅ Done 2026-03-21 — CI deploys all files to `/volume1/Synology Monitor/` on push to `main`. Protected files (`synology_data.json`, `synology_monitor.log`) excluded from rsync.

- **Build and start Docker container** ✅ Done 2026-03-21 — `synology-monitor-api` container running on port 5051, volume-mounted from `/volume1/Synology Monitor`.

- **Schedule monitor script** ✅ Done 2026-03-21 — Task Scheduler runs `python3 "/volume1/Synology Monitor/synology_monitor.py"` every 5 minutes as root.

- **Install Hubitat driver** ✅ Done 2026-03-21 — Driver installed, virtual device created, Flask API URL set to `http://192.168.10.175:5051`. Dashboard tile uses `nasSummary` attribute with `<br>`-separated lines and emoji dots.

- **Verify end-to-end** ✅ Done 2026-03-21 — API returns valid JSON, all attributes populate, `nasSummary` tile displays correctly on Hubitat dashboard.

- **Exclude root filesystem** ✅ Done 2026-03-21 — Removed `/` from storage candidate paths; only `/volume1`, `/volume2`, `/volume3` are reported.

- **Automatic Hubitat driver deployment** ✅ Done 2026-03-21 — `deploy_hubitat.py` logs into the Hubitat hub via its local HTTP API, finds the driver by name, fetches the current version, and pushes updated source. Called automatically from `deploy.yml` after the rsync step on every push to `main`. Hub credentials stored as GitHub repository secrets (`HUBITAT_IP`, `HUBITAT_USER`, `HUBITAT_PASS`). Hub auth is optional — script skips login if the hub allows unauthenticated local access.

- **Docker container health row** ✅ Done 2026-03-21 — `synology_monitor.py` calls `docker ps` to count total and running containers. Driver publishes `containersTotal` / `containersRunning` attributes and adds a single "x of y running" line (with emoji dot) to the `nasSummary` tile. 🟢 = all running, 🟡 = some down, 🔴 = all down, ⚪ = unavailable.
