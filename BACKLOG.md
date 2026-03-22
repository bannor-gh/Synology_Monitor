# Backlog

## Open

1. **Register self-hosted runner** — Register a new runner in Synology Container Manager using `myoung34/github-runner:latest`.
   - Runner name: `github-runner-synology`, label: `synology-monitor`
   - Volume mount: `/volume1/Synology Monitor` → `/synology-monitor/` (use no-space path inside container)
   - `deploy.yml` already targets `runs-on: synology-monitor`
   - See `docs/architecture.md` → Setup Plan → Step 2 for the full `docker run` command

2. **Create NAS working directory** — Create `/volume1/Synology Monitor/` on the NAS via File Station or SSH before first deploy.

3. **First deploy** — Push to `main` triggers CI; runner rsyncs all files to `/synology-monitor/`.
   - Protected files that must never be overwritten: `synology_data.json`, `synology_monitor.log` (already excluded in `deploy.yml`)

4. **Build and start Docker container** — SSH into NAS or use Container Manager:
   - Build: `docker build -t synology-monitor-api .` from `/volume1/Synology Monitor/`
   - Run on port 5051: `docker run -d --name synology-monitor-api --restart unless-stopped -p 5051:5000 -v "/volume1/Synology Monitor:/app" synology-monitor-api`

5. **Schedule monitor script** — In Synology Task Scheduler, create a User-defined script task:
   - Schedule: every 5 minutes
   - Command: `python3 "/volume1/Synology Monitor/synology_monitor.py"`
   - Run as: root (required for `/proc` access)

6. **Install Hubitat driver** — In Hubitat → Drivers Code → New Driver → paste `synology_hubitat_driver.groovy` → Save.
   - Add Virtual Device → select "Synology NAS Monitor"
   - Set Flask API Base URL to `http://192.168.10.175:5051`
   - Optionally adjust warn/critical thresholds (defaults: CPU 50/80%, RAM 70/85%, Disk 70/85%)
   - Save Preferences — auto-refresh schedule activates immediately

7. **Verify end-to-end** — Confirm the full data pipeline is working:
   - `GET http://192.168.10.175:5051/synology` returns valid JSON
   - Click **fetchSynologyData** on Hubitat device page and confirm all attributes populate
   - Add an **Attribute** tile to a Hubitat dashboard → select Synology NAS Monitor device → attribute `systemSummary`
   - Confirm colored dots appear and values look correct

## Done

- **Repo created and seeded** ✅ Done 2026-03-19 — `Synology_Monitor` repo created on GitHub, all files committed from design doc: `synology_monitor.py`, `app.py`, `Dockerfile`, `requirements.txt`, `synology_hubitat_driver.groovy`, `.github/workflows/deploy.yml`, `docs/architecture.md`.

- **Color status dots on dashboard tile** ✅ Done 2026-03-19 — `systemSummary` renders an HTML table with a green/yellow/red dot per metric (CPU, RAM, each volume). Thresholds are configurable driver preferences; defaults are CPU 50/80%, RAM 70/85%, Disk 70/85%.

- **Docker container health row** ✅ Done 2026-03-21 — `synology_monitor.py` calls `docker ps` to count total and running containers. Driver publishes `containersTotal` / `containersRunning` attributes and adds a single "x of y running" row (with colored dot) to the `systemSummary` tile. Green = all running, yellow = some down, red = all down.
