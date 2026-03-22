# Synology NAS Monitor — Design Document

Standalone project to monitor Synology NAS health and surface the data as
Hubitat device attributes. Mirrors the Generac integration pattern: a Python
script on the NAS writes JSON → a Dockerized Flask API serves it → a Hubitat
Groovy driver polls and publishes attributes.

---

## Goals

- Report CPU, RAM, swap, network throughput, per-volume disk, Docker container
  state, RAID health, and drive SMART status from the Synology NAS.
- Expose the data via a local HTTP API so Hubitat can poll it.
- Provide two dashboard tiles from a single driver:
  - **`nasSummary`** — compact tile: CPU, RAM, disk %, container count with dots.
  - **`expandedSummary`** — full health tile: all of the above plus swap, network
    throughput per interface, RAID array status, SMART pass/fail, stopped container
    names, and system uptime.
- Expose individual numeric attributes for use in Rule Machine automations.
- Require no cloud services and no Synology DSM credentials — everything reads
  from `/proc`, the `docker` CLI, `/proc/mdstat`, and `smartctl`.
- Auto-deploy the Hubitat driver on every push to `main` via the hub's local HTTP API.

---

## Architecture

```
Synology Task Scheduler (every 5 minutes, runs as root)
  → synology_monitor.py wakes up
  → Shared 1-second sleep: samples /proc/stat (CPU delta) + /proc/net/dev (network delta)
  → Reads /proc/meminfo (RAM + swap), /proc/loadavg (load), /proc/uptime (uptime)
  → Reads os.statvfs(/volume1), os.statvfs(/volume2) (disk — /volume* only)
  → Calls docker ps -a --format to get per-container name + state
  → Reads /proc/mdstat for RAID array health
  → Calls smartctl -H /dev/sdX for each drive (SMART pass/fail)
  → Writes synology_data.json
  → Logs to synology_monitor.log (rotating, 3 × 2 MB)

Docker container: synology-monitor-api (port 5051)
  → app.py (Flask)
  → GET /synology  →  returns synology_data.json
  → Volume mount: /volume1/Synology Monitor → /app

Hubitat Elevation (Rule Machine refresh schedule)
  → synology_hubitat_driver.groovy polls GET /synology
  → Publishes attributes:
      cpuPercent, loadAvg1min, loadAvg5min,
      memoryPercent, memoryUsedMB, memoryTotalMB, swapPercent,
      uptimeDays,
      volume1Path, volume1UsedGB, volume1TotalGB, volume1Percent,
      volume2Path, volume2UsedGB, volume2TotalGB, volume2Percent,
      containersTotal, containersRunning,
      lastUpdate,
      nasSummary      ← compact tile (CPU/RAM/disk/containers)
      expandedSummary ← full health tile (all above + swap/network/RAID/SMART/uptime)

GitHub Actions (push to main)
  → Self-hosted runner (synology-monitor label) on NAS
  → Step 1: rsync all files to /volume1/Synology Monitor/
  → Step 2: deploy_hubitat.py pushes updated driver to Hubitat hub via local HTTP API
```

---

## Repository Structure

```
/
├── synology_monitor.py          ← Runs on NAS via Task Scheduler
├── app.py                       ← Flask API (Docker)
├── Dockerfile
├── requirements.txt             ← flask only
├── synology_hubitat_driver.groovy
├── deploy_hubitat.py            ← Pushes driver to Hubitat hub via local HTTP API
├── .github/
│   └── workflows/
│       └── deploy.yml           ← Self-hosted runner → rsync to NAS + Hubitat deploy
└── docs/
    └── architecture.md

/volume1/Synology Monitor/       ← Live directory on NAS (not in git)
    synology_monitor.py          ← Deployed by CI
    app.py                       ← Deployed by CI
    Dockerfile                   ← Deployed by CI
    requirements.txt             ← Deployed by CI
    deploy_hubitat.py            ← Deployed by CI
    synology_data.json           ← Written by monitor script (not in git)
    synology_monitor.log         ← Rotating log (not in git)
```

**Port allocation:** Use `5051` to avoid collision with the Generac API on `5050`.

---

## Code

### `synology_monitor.py`

```python
#!/usr/bin/env python3
"""
synology_monitor.py - Collect Synology NAS system metrics.

Reads CPU, memory, and storage stats from /proc and the filesystem,
then saves to synology_data.json for the Flask API to serve.

Run via Synology Task Scheduler (e.g. every 5 minutes).
"""

import json
import time
import os
import subprocess
import logging
from logging.handlers import RotatingFileHandler
from datetime import datetime, timezone

BASE_DIR = "/volume1/Synology Monitor"
OUTPUT_FILE = os.path.join(BASE_DIR, "synology_data.json")
LOG_FILE = os.path.join(BASE_DIR, "synology_monitor.log")

handler = RotatingFileHandler(LOG_FILE, maxBytes=2 * 1024 * 1024, backupCount=3)
logging.basicConfig(
    handlers=[handler],
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger(__name__)


def get_cpu_percent(interval=1.0):
    def read_stat():
        with open("/proc/stat") as f:
            line = f.readline()
        fields = list(map(int, line.split()[1:]))
        idle = fields[3]
        total = sum(fields)
        return idle, total

    idle1, total1 = read_stat()
    time.sleep(interval)
    idle2, total2 = read_stat()

    delta_idle = idle2 - idle1
    delta_total = total2 - total1
    if delta_total == 0:
        return 0.0
    return round((1.0 - delta_idle / delta_total) * 100, 1)


def get_memory_info():
    mem = {}
    with open("/proc/meminfo") as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 2:
                key = parts[0].rstrip(":")
                mem[key] = int(parts[1])

    total = mem.get("MemTotal", 0)
    available = mem.get("MemAvailable", mem.get("MemFree", 0))
    used = total - available

    return {
        "total_mb": round(total / 1024, 1),
        "used_mb": round(used / 1024, 1),
        "available_mb": round(available / 1024, 1),
        "percent": round(used / total * 100, 1) if total > 0 else 0.0,
    }


def get_load_average():
    with open("/proc/loadavg") as f:
        parts = f.read().split()
    return {
        "1min": float(parts[0]),
        "5min": float(parts[1]),
        "15min": float(parts[2]),
    }


def get_storage_info():
    """Return disk usage for each Synology volume. Root / is excluded."""
    candidate_paths = ["/volume1", "/volume2", "/volume3"]
    volumes = []
    seen_totals = set()

    for path in candidate_paths:
        if not os.path.exists(path):
            continue
        try:
            stat = os.statvfs(path)
            total = stat.f_blocks * stat.f_frsize
            free = stat.f_bavail * stat.f_frsize
            used = total - free

            if total == 0 or total in seen_totals:
                continue
            seen_totals.add(total)

            volumes.append({
                "path": path,
                "total_gb": round(total / (1024 ** 3), 2),
                "used_gb": round(used / (1024 ** 3), 2),
                "free_gb": round(free / (1024 ** 3), 2),
                "percent": round(used / total * 100, 1),
            })
        except OSError as e:
            log.warning("Could not stat %s: %s", path, e)

    return volumes


def get_docker_info():
    """Return total and running container counts via the docker CLI."""
    try:
        all_result = subprocess.run(
            ["docker", "ps", "-a", "-q"],
            capture_output=True, text=True, timeout=10,
        )
        running_result = subprocess.run(
            ["docker", "ps", "-q"],
            capture_output=True, text=True, timeout=10,
        )
        total   = len([l for l in all_result.stdout.strip().splitlines() if l])
        running = len([l for l in running_result.stdout.strip().splitlines() if l])
        return {"total": total, "running": running}
    except Exception as e:
        log.warning("Could not query Docker: %s", e)
        return {"total": None, "running": None}


def collect_metrics():
    log.info("Collecting Synology metrics...")

    cpu_percent = get_cpu_percent(interval=1.0)
    memory = get_memory_info()
    load = get_load_average()
    storage = get_storage_info()
    docker = get_docker_info()

    data = {
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "cpu": {"percent": cpu_percent},
        "load_average": load,
        "memory": memory,
        "storage": storage,
        "docker": docker,
    }

    with open(OUTPUT_FILE, "w") as f:
        json.dump(data, f, indent=2)

    log.info("Metrics saved — CPU: %s%%, MEM: %s%%, Volumes: %d, Containers: %s/%s",
             cpu_percent, memory["percent"], len(storage),
             docker.get("running"), docker.get("total"))
    return data


if __name__ == "__main__":
    collect_metrics()
```

---

### `app.py`

```python
from flask import Flask, jsonify
import os
import json

app = Flask(__name__)

BASE_DIR = "/app"
SYNOLOGY_FILE = os.path.join(BASE_DIR, "synology_data.json")


def read_json_file(filepath):
    try:
        with open(filepath, "r") as f:
            return json.load(f)
    except Exception as e:
        return {"error": str(e)}


@app.route("/synology", methods=["GET"])
def get_synology_data():
    return jsonify(read_json_file(SYNOLOGY_FILE))


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000)
```

---

### `Dockerfile`

```dockerfile
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .

EXPOSE 5000

CMD ["python", "app.py"]
```

---

### `requirements.txt`

```
flask
```

---

### `deploy_hubitat.py`

Pushes the updated Groovy driver to the Hubitat hub via its local (unauthenticated) HTTP API.
Called by `deploy.yml` on every push to `main`.

```python
#!/usr/bin/env python3
"""
deploy_hubitat.py — Push updated Groovy driver code to a Hubitat hub.

Usage:
    python3 deploy_hubitat.py <driver_file.groovy>

Required environment variables:
    HUBITAT_IP    — Hub IP or hostname (e.g. 192.168.10.100)
    HUBITAT_USER  — Hub admin username (used only if hub requires auth)
    HUBITAT_PASS  — Hub admin password (used only if hub requires auth)
"""

import os, sys, requests

DRIVER_NAME = "Synology NAS Monitor"


def needs_auth(session, base_url):
    resp = session.get(f"{base_url}/driver/list/data", allow_redirects=True, timeout=15)
    return "/login" in resp.url or resp.status_code == 401


def login(session, base_url, user, password):
    if not needs_auth(session, base_url):
        print("Hub requires no authentication — skipping login.")
        return
    resp = session.post(f"{base_url}/login",
                        data={"username": user, "password": password, "submit": "Login"},
                        allow_redirects=True, timeout=15)
    resp.raise_for_status()
    if "/login" in resp.url:
        raise RuntimeError("Hubitat login failed — check HUBITAT_USER / HUBITAT_PASS")
    print("Logged in to Hubitat.")


def find_driver_id(session, base_url, name):
    resp = session.get(f"{base_url}/driver/list/data", timeout=15)
    resp.raise_for_status()
    for d in resp.json():
        if d.get("name") == name and d.get("type") == "usr":
            return d["id"]
    raise RuntimeError(f"Driver '{name}' not found. Install it manually once first.")


def get_current_version(session, base_url, driver_id):
    resp = session.get(f"{base_url}/driver/ajax/code", params={"id": driver_id}, timeout=15)
    resp.raise_for_status()
    data = resp.json()
    if data.get("status") != "success":
        raise RuntimeError(f"Failed to fetch driver version: {data}")
    return data["version"]


def update_driver(session, base_url, driver_id, version, source):
    resp = session.post(f"{base_url}/driver/ajax/update",
                        data={"id": driver_id, "version": version, "source": source},
                        timeout=30)
    resp.raise_for_status()
    data = resp.json()
    if data.get("status") != "success":
        raise RuntimeError(f"Driver update failed: {data.get('errorMessage', data)}")
    print(f"Driver updated — new version: {data['version']}")


def main():
    if len(sys.argv) != 2:
        print("Usage: deploy_hubitat.py <driver_file.groovy>")
        sys.exit(1)

    groovy_file = sys.argv[1]
    hub_ip   = os.environ.get("HUBITAT_IP")
    hub_user = os.environ.get("HUBITAT_USER")
    hub_pass = os.environ.get("HUBITAT_PASS")

    if not all([hub_ip, hub_user, hub_pass]):
        print("Error: HUBITAT_IP, HUBITAT_USER, and HUBITAT_PASS must be set.")
        sys.exit(1)

    with open(groovy_file) as f:
        source = f.read()

    base_url = f"http://{hub_ip}"
    session  = requests.Session()

    login(session, base_url, hub_user, hub_pass)
    driver_id = find_driver_id(session, base_url, DRIVER_NAME)
    print(f"Found driver '{DRIVER_NAME}' — id={driver_id}")
    version = get_current_version(session, base_url, driver_id)
    print(f"Current version: {version}")
    update_driver(session, base_url, driver_id, version, source)
    print("Hubitat driver deploy complete.")


if __name__ == "__main__":
    main()
```

---

### `.github/workflows/deploy.yml`

```yaml
name: Deploy to Synology

on:
  push:
    branches: [main]

jobs:
  deploy:
    runs-on: synology-monitor

    steps:
      - name: Check out code
        uses: actions/checkout@v4

      - name: Deploy files to Synology Monitor
        run: |
          rsync -av --chmod=Du=rwx,Dg=rx,Do=rx,Fu=rw,Fg=r,Fo=r \
                    --exclude='synology_data.json' \
                    --exclude='synology_monitor.log' \
                    --exclude='.git/' \
                    $GITHUB_WORKSPACE/ "/synology-monitor/"
          chmod -R 777 "/synology-monitor/" || true

      - name: Deploy driver to Hubitat
        env:
          HUBITAT_IP:   ${{ secrets.HUBITAT_IP }}
          HUBITAT_USER: ${{ secrets.HUBITAT_USER }}
          HUBITAT_PASS: ${{ secrets.HUBITAT_PASS }}
        run: |
          pip3 install requests --quiet
          python3 /synology-monitor/deploy_hubitat.py /synology-monitor/synology_hubitat_driver.groovy
```

> **Secrets** — `HUBITAT_IP`, `HUBITAT_USER`, and `HUBITAT_PASS` are stored as
> GitHub repository secrets. They are never committed to the repo; GitHub injects
> them at runtime only.

> **Runner** — `myoung34/github-runner:latest` container on the NAS, registered
> with label `synology-monitor`. Volume mount: `/volume1/Synology Monitor` → `/synology-monitor/`.

---

### `synology_hubitat_driver.groovy`

Key points:
- `nasSummary` attribute renders a `<br>`-separated string with emoji dots (🟢🟡🔴) for the dashboard tile
- `capability "Refresh"` + `capability "Sensor"` for Hubitat dashboard compatibility
- Thresholds are configurable preferences (CPU 50/80%, RAM 70/85%, Disk 70/85%)
- Auto-refresh via cron schedule set in `updated()`

See the file in the repo root for the full current source.

---

## JSON Output Format

`synology_data.json` written by the monitor script:

```json
{
  "timestamp": "2026-03-21T06:05:02+00:00",
  "cpu": {
    "percent": 12.4
  },
  "load_average": {
    "1min": 0.45,
    "5min": 0.38,
    "15min": 0.31
  },
  "memory": {
    "total_mb": 32071.8,
    "used_mb": 11816.8,
    "available_mb": 20255.0,
    "percent": 36.8
  },
  "storage": [
    {
      "path": "/volume1",
      "total_gb": 14287.68,
      "used_gb": 2807.04,
      "free_gb": 11480.64,
      "percent": 19.6
    },
    {
      "path": "/volume2",
      "total_gb": 1777.92,
      "used_gb": 1032.89,
      "free_gb": 745.03,
      "percent": 58.1
    }
  ],
  "docker": {
    "total": 10,
    "running": 10
  }
}
```

---

## Setup Plan

### 1. Create the repository

Create a new GitHub repo named `Synology_Monitor` and add all files.

### 2. Register a self-hosted runner

Run on the NAS via Synology Container Manager or SSH:

```bash
/usr/local/bin/docker run -d \
  --name github-runner-synology \
  --restart unless-stopped \
  -e REPO_URL="https://github.com/bannor-gh/Synology_Monitor" \
  -e ACCESS_TOKEN="<github-pat-with-repo-scope>" \
  -e RUNNER_NAME="github-runner-synology" \
  -e RUNNER_LABELS="synology-monitor" \
  -v "/volume1/Synology Monitor:/synology-monitor" \
  myoung34/github-runner:latest
```

**Uses `ACCESS_TOKEN` (GitHub PAT) instead of a one-time `RUNNER_TOKEN`** — the runner re-registers itself automatically on every NAS restart. Create a PAT at GitHub → Settings → Developer Settings → Personal Access Tokens (classic) with `repo` scope. The same PAT is shared across all NAS runners.

### 3. Add GitHub repository secrets

In **GitHub repo → Settings → Secrets and variables → Actions**:

| Secret | Value |
|---|---|
| `HUBITAT_IP` | Hub IP address (e.g. `192.168.10.x`) |
| `HUBITAT_USER` | Hub admin username |
| `HUBITAT_PASS` | Hub admin password |

### 4. Create the NAS working directory

```
/volume1/Synology Monitor/
```

### 5. Push to main — CI deploys all files and updates the Hubitat driver

### 6. Build and start the Docker container (one-time, manual)

```bash
cd "/volume1/Synology Monitor"
docker build -t synology-monitor-api .
docker run -d \
  --name synology-monitor-api \
  --restart unless-stopped \
  -p 5051:5000 \
  -v "/volume1/Synology Monitor:/app" \
  synology-monitor-api
```

### 7. Schedule the monitor script (one-time, manual)

In Synology Task Scheduler, create a **User-defined script** task:

- **Schedule:** Every 5 minutes
- **Run as:** root (required for `/proc` access)
- **Command:** `python3 "/volume1/Synology Monitor/synology_monitor.py"`

### 8. Install the Hubitat driver (one-time, manual)

The driver auto-deploys on every subsequent push. The first install must be done manually:

1. In Hubitat → **Drivers Code** → **New Driver** → paste `synology_hubitat_driver.groovy` → **Save**
2. **Devices** → **Add Virtual Device** → select "Synology NAS Monitor"
3. Set **Flask API Base URL** to `http://192.168.10.175:5051`
4. Optionally adjust warn/critical thresholds (defaults: CPU 50/80%, RAM 70/85%, Disk 70/85%)
5. Click **Save Preferences** — the auto-refresh schedule activates immediately

### 9. Add dashboard tile

- Add an **Attribute** tile → select Synology NAS Monitor device → attribute `nasSummary`
- Resize to at least 2 columns wide for best display

### 10. Verify

- `GET http://192.168.10.175:5051/synology` returns valid JSON
- Click **fetchSynologyData** on the device page — all attributes populate
- `nasSummary` tile shows emoji dots + percentages for CPU, RAM, and each volume

---

## Decisions

| Decision | Choice | Reason |
|---|---|---|
| Metrics source | `/proc` filesystem + `os.statvfs` | No extra dependencies, no DSM credentials needed |
| API port | 5051 | Avoids collision with Generac API on 5050 |
| Separate repo | Yes | Different lifecycle, different deployment target, cleaner ownership |
| Runner | New runner (`synology-monitor`) | Allows independent deploy pipelines per project |
| Refresh granularity | 5 minutes (configurable) | Matches Generac cadence; NAS metrics don't change faster |
| Root filesystem excluded | Yes | `/` is the DSM OS partition (~2 GB); not meaningful to monitor |
| Dashboard tiles | Two: `nasSummary` (compact) + `expandedSummary` (full) | Compact tile fits existing small dashboard; expanded tile added to a separate full-health dashboard as container/VM count grows |
| Dot style | Emoji (🟢🟡🔴) | HTML `<span>` tags are stripped by Hubitat dashboard; emoji render reliably |
| Dot thresholds | Configurable driver preferences | Different environments may have different baselines |
| Shared 1-second sleep | CPU and network delta share one `time.sleep(1.0)` | Avoids adding a second blocking pause; total script runtime stays ~1 second |
| Network interfaces | Exclude `lo`, `docker0`, `br-*`, `veth*` | Docker virtual interfaces add noise; physical and bond interfaces are useful |
| SMART check | `smartctl -H /dev/sdX` per drive | Lightweight overall-health check only; full SMART report not needed for dashboard |
| Hubitat auto-deploy | `deploy_hubitat.py` via local HTTP API | Eliminates manual copy/paste on every driver change; no cloud dependency |
| Hub credentials | GitHub repository secrets | Encrypted at rest, never committed, injected at runtime only |
| Docker container count | `docker ps` via subprocess | No extra libraries; works on any Docker install; graceful fallback if Docker unavailable |
| Container "healthy" definition | Running (Up) vs total | Not all containers define a Docker health check; running/total is universal and meaningful |
