# Synology NAS Monitor — Design Document

Standalone project to monitor Synology NAS health (CPU, memory, storage) and
surface the data as Hubitat device attributes. Mirrors the Generac integration
pattern: a Python script on the NAS writes JSON → a Dockerized Flask API serves
it → a Hubitat Groovy driver polls and publishes attributes.

---

## Goals

- Report real-time CPU usage, RAM usage, and per-volume disk usage from the
  Synology NAS.
- Expose the data via a local HTTP API so Hubitat can poll it.
- Display a `systemSummary` tile on a Hubitat dashboard showing CPU, RAM, and
  each volume with a green/yellow/red status dot and usage details.
- Expose individual numeric attributes for use in Rule Machine automations.
- Require no cloud services and no Synology DSM credentials — everything reads
  directly from the Linux `/proc` filesystem.

---

## Architecture

```
Synology Task Scheduler (every 5 minutes)
  → synology_monitor.py wakes up
  → Reads /proc/stat (CPU), /proc/meminfo (RAM), /proc/loadavg (load)
  → Reads os.statvfs(/volume1), os.statvfs(/volume2) (disk)
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
      memoryPercent, memoryUsedMB, memoryTotalMB,
      volume1Path, volume1UsedGB, volume1TotalGB, volume1Percent,
      volume2Path, volume2UsedGB, volume2TotalGB, volume2Percent,
      containersTotal, containersRunning,
      lastUpdate, systemSummary
```

---

## Repository Structure

New repository: **`Synology_Monitor`** (separate from `Generac_Hubitat`)

```
/
├── synology_monitor.py          ← Runs on NAS via Task Scheduler
├── app.py                       ← Flask API (Docker)
├── Dockerfile
├── requirements.txt             ← flask only
├── synology_hubitat_driver.groovy
├── .github/
│   └── workflows/
│       └── deploy.yml           ← Self-hosted runner → rsync to NAS
└── docs/
    └── architecture.md

/volume1/Synology Monitor/       ← Live directory on NAS (not in git)
    synology_monitor.py          ← Deployed by CI
    app.py                       ← Deployed by CI
    Dockerfile                   ← Deployed by CI
    requirements.txt             ← Deployed by CI
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
    """Calculate CPU usage % by sampling /proc/stat over an interval."""

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
    """Parse /proc/meminfo for memory stats."""
    mem = {}
    with open("/proc/meminfo") as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 2:
                key = parts[0].rstrip(":")
                mem[key] = int(parts[1])  # values are in kB

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
    """Return 1-, 5-, and 15-minute load averages from /proc/loadavg."""
    with open("/proc/loadavg") as f:
        parts = f.read().split()
    return {
        "1min": float(parts[0]),
        "5min": float(parts[1]),
        "15min": float(parts[2]),
    }


def get_storage_info():
    """Return disk usage for each Synology volume found on the system."""
    candidate_paths = ["/volume1", "/volume2", "/volume3", "/"]
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

            volumes.append(
                {
                    "path": path,
                    "total_gb": round(total / (1024 ** 3), 2),
                    "used_gb": round(used / (1024 ** 3), 2),
                    "free_gb": round(free / (1024 ** 3), 2),
                    "percent": round(used / total * 100, 1),
                }
            )
        except OSError as e:
            log.warning("Could not stat %s: %s", path, e)

    return volumes


def collect_metrics():
    log.info("Collecting Synology metrics...")

    cpu_percent = get_cpu_percent(interval=1.0)
    memory = get_memory_info()
    load = get_load_average()
    storage = get_storage_info()

    data = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "cpu": {
            "percent": cpu_percent,
        },
        "load_average": load,
        "memory": memory,
        "storage": storage,
    }

    with open(OUTPUT_FILE, "w") as f:
        json.dump(data, f, indent=2)

    log.info(
        "Metrics saved — CPU: %s%%, MEM: %s%%, Volumes: %d",
        cpu_percent,
        memory["percent"],
        len(storage),
    )
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

BASE_DIR = "/app"  # Docker container path; volume-mounted from NAS
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

### `synology_hubitat_driver.groovy`

```groovy
metadata {
    definition(name: "Synology NAS Monitor", namespace: "yourNamespace", author: "Your Name") {
        capability "Refresh"
        command "fetchSynologyData"

        attribute "cpuPercent",       "number"
        attribute "loadAvg1min",      "number"
        attribute "loadAvg5min",      "number"
        attribute "memoryPercent",    "number"
        attribute "memoryUsedMB",     "number"
        attribute "memoryTotalMB",    "number"
        attribute "volume1Path",      "string"
        attribute "volume1UsedGB",    "number"
        attribute "volume1TotalGB",   "number"
        attribute "volume1Percent",   "number"
        attribute "volume2Path",      "string"
        attribute "volume2UsedGB",    "number"
        attribute "volume2TotalGB",   "number"
        attribute "volume2Percent",   "number"
        attribute "lastUpdate",       "string"
        attribute "systemSummary",    "string"
    }
}

preferences {
    input name: "flaskBaseUrl",     type: "text",   title: "Flask API Base URL (e.g., http://192.168.10.175:5051)", required: true
    input name: "refreshInterval",  type: "number", title: "Refresh interval (minutes)", defaultValue: 5, required: true
    input name: "cpuWarn",          type: "number", title: "CPU warn threshold (%)",     defaultValue: 50, required: true
    input name: "cpuCrit",          type: "number", title: "CPU critical threshold (%)", defaultValue: 80, required: true
    input name: "memWarn",          type: "number", title: "Memory warn threshold (%)",     defaultValue: 70, required: true
    input name: "memCrit",          type: "number", title: "Memory critical threshold (%)", defaultValue: 85, required: true
    input name: "diskWarn",         type: "number", title: "Disk warn threshold (%)",     defaultValue: 70, required: true
    input name: "diskCrit",         type: "number", title: "Disk critical threshold (%)", defaultValue: 85, required: true
}

def installed() {
    updated()
}

def updated() {
    unschedule()
    def interval = (refreshInterval ?: 5) as int
    schedule("0 */${interval} * * * ?", refresh)
    log.info "Synology Monitor: refresh scheduled every ${interval} minute(s)"
}

def refresh() {
    fetchSynologyData()
}

def fetchSynologyData() {
    if (!flaskBaseUrl) {
        log.error "Flask API Base URL not set!"
        return
    }

    def url = "${flaskBaseUrl}/synology"
    try {
        httpGet(url) { resp ->
            if (resp.status == 200) {
                def json = resp.data
                log.debug "Synology data received: ${json}"
                parseSynologyData(json)
            } else {
                log.error "Failed to fetch Synology data: HTTP ${resp.status}"
            }
        }
    } catch (e) {
        log.error "Error fetching Synology data: ${e.message}"
    }
}

private void parseSynologyData(json) {
    // CPU
    def cpu = json.cpu
    if (cpu) {
        sendEvent(name: "cpuPercent", value: cpu.percent, unit: "%")
    }

    // Load average
    def load = json.load_average
    if (load) {
        sendEvent(name: "loadAvg1min", value: load["1min"])
        sendEvent(name: "loadAvg5min", value: load["5min"])
    }

    // Memory
    def mem = json.memory
    if (mem) {
        sendEvent(name: "memoryPercent", value: mem.percent,  unit: "%")
        sendEvent(name: "memoryUsedMB",  value: mem.used_mb,  unit: "MB")
        sendEvent(name: "memoryTotalMB", value: mem.total_mb, unit: "MB")
    }

    // Storage — report up to two volumes
    def volumes = json.storage
    if (volumes && volumes.size() > 0) {
        def v1 = volumes[0]
        sendEvent(name: "volume1Path",    value: v1.path)
        sendEvent(name: "volume1UsedGB",  value: v1.used_gb,  unit: "GB")
        sendEvent(name: "volume1TotalGB", value: v1.total_gb, unit: "GB")
        sendEvent(name: "volume1Percent", value: v1.percent,  unit: "%")
    }
    if (volumes && volumes.size() > 1) {
        def v2 = volumes[1]
        sendEvent(name: "volume2Path",    value: v2.path)
        sendEvent(name: "volume2UsedGB",  value: v2.used_gb,  unit: "GB")
        sendEvent(name: "volume2TotalGB", value: v2.total_gb, unit: "GB")
        sendEvent(name: "volume2Percent", value: v2.percent,  unit: "%")
    }

    // Timestamp
    def ts = json.timestamp ?: "unknown"
    try {
        def dateObj = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", ts)
        ts = dateObj.format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    } catch (e) {
        log.warn "Could not parse timestamp: ${ts}"
    }
    sendEvent(name: "lastUpdate", value: ts)

    // Summary tile with colored status dots
    // dot() returns a green/yellow/red ● based on warn/crit thresholds
    try {
        def cpuVal  = (json.cpu?.percent  ?: 0) as BigDecimal
        def memVal  = (json.memory?.percent ?: 0) as BigDecimal
        def memUsed = json.memory?.used_mb  ?: 0
        def memTot  = json.memory?.total_mb ?: 0

        def cW = (cpuWarn  ?: 50) as BigDecimal; def cC = (cpuCrit  ?: 80) as BigDecimal
        def mW = (memWarn  ?: 70) as BigDecimal; def mC = (memCrit  ?: 85) as BigDecimal
        def dW = (diskWarn ?: 70) as BigDecimal; def dC = (diskCrit ?: 85) as BigDecimal

        def lines = "<table style=\"border-collapse:collapse; width:100%; font-size:0.9em;\">"
        lines += "<tr><td>${dot(cpuVal,cW,cC)}</td><td>CPU</td><td style=\"text-align:right;\">${cpuVal}%</td></tr>"
        lines += "<tr><td>${dot(memVal,mW,mC)}</td><td>RAM</td><td style=\"text-align:right;\">${memVal}% (${memUsed}/${memTot} MB)</td></tr>"
        volumes?.each { vol ->
            def pct = (vol.percent ?: 0) as BigDecimal
            lines += "<tr><td>${dot(pct,dW,dC)}</td><td>${vol.path}</td><td style=\"text-align:right;\">${pct}% (${vol.used_gb}/${vol.total_gb} GB)</td></tr>"
        }
        lines += "</table><div style=\"font-size:0.75em; opacity:0.6;\">Updated ${ts}</div>"
        sendEvent(name: "systemSummary", value: lines)
    } catch (e) {
        log.warn "Failed to build systemSummary: ${e.message}"
    }
}

private String dot(BigDecimal value, BigDecimal warn, BigDecimal crit) {
    def color = value >= crit ? "#e74c3c" : value >= warn ? "#f39c12" : "#2ecc71"
    return "<span style=\"color:${color}; font-size:1.2em;\">&#11044;</span>"
}
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
    runs-on: self-hosted

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
```

> The self-hosted runner mounts `/volume1/Synology Monitor` as `/synology-monitor/`
> inside its container (same pattern as the Generac runner).

---

## JSON Output Format

`synology_data.json` written by the monitor script:

```json
{
  "timestamp": "2026-03-19T14:30:00+00:00",
  "cpu": {
    "percent": 12.4
  },
  "load_average": {
    "1min": 0.45,
    "5min": 0.38,
    "15min": 0.31
  },
  "memory": {
    "total_mb": 32768.0,
    "used_mb": 18432.0,
    "available_mb": 14336.0,
    "percent": 56.2
  },
  "storage": [
    {
      "path": "/volume1",
      "total_gb": 14.55,
      "used_gb": 9.12,
      "free_gb": 5.43,
      "percent": 62.7
    },
    {
      "path": "/volume2",
      "total_gb": 7.28,
      "used_gb": 3.41,
      "free_gb": 3.87,
      "percent": 46.8
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

Create a new GitHub repo named `Synology_Monitor` and add all files above.

### 2. Register a self-hosted runner

Use the same `myoung34/github-runner` approach as the Generac runner, but with
a different runner name and label so GitHub Actions can target it independently.

```bash
docker run -d \
  --name github-runner-synology \
  --restart unless-stopped \
  -e REPO_URL="https://github.com/<your-org>/Synology_Monitor" \
  -e RUNNER_NAME="synology-nas-monitor" \
  -e RUNNER_LABELS="synology-monitor" \
  -e ACCESS_TOKEN="<github-pat>" \
  -v "/volume1/Synology Monitor:/synology-monitor" \
  myoung34/github-runner:latest
```

Update `deploy.yml` `runs-on:` to `synology-monitor` to match the label.

### 3. Create the NAS working directory

```
/volume1/Synology Monitor/
```

### 4. Push to main — CI deploys all files

### 5. Build and start the Docker container

SSH into the NAS or use Synology Container Manager:

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

### 6. Schedule the monitor script

In Synology Task Scheduler, create a **User-defined script** task:

- **Schedule:** Every 5 minutes
- **Command:** `python3 "/volume1/Synology Monitor/synology_monitor.py"`

### 7. Install the Hubitat driver

1. In Hubitat → **Drivers Code** → **New Driver** → paste `synology_hubitat_driver.groovy` → **Save**
2. **Devices** → **Add Virtual Device** → select "Synology NAS Monitor"
3. Set **Flask API Base URL** to `http://192.168.10.175:5051`
4. Set **Refresh interval** (default 5 minutes)
5. Optionally adjust warn/critical thresholds (defaults: CPU 50/80%, RAM 70/85%, Disk 70/85%)
6. Click **Save Preferences** — the auto-refresh schedule activates immediately

### 8. Verify

- Check `GET http://192.168.10.175:5051/synology` returns valid JSON
- On the Hubitat device page, click **fetchSynologyData** and confirm attributes populate
- Add an **Attribute** tile to a Hubitat dashboard, select the Synology NAS Monitor device, attribute `systemSummary` — renders as an HTML health summary with colored dots

---

## Decisions

| Decision | Choice | Reason |
|---|---|---|
| Metrics source | `/proc` filesystem + `os.statvfs` | No extra dependencies, no DSM credentials needed, runs directly on NAS |
| API port | 5051 | Avoids collision with Generac API on 5050 |
| Separate repo | Yes | Different lifecycle, different deployment target, cleaner ownership |
| Runner | New runner (`synology-monitor`) | Allows independent deploy pipelines per project |
| Refresh granularity | 5 minutes (configurable) | Matches Generac cadence; NAS metrics don't change faster than this |
| Dashboard tile | `systemSummary` HTML attribute tile | Single tile shows all metrics at a glance; colored dots (green/yellow/red) give instant health status without opening the device page |
| Dot thresholds | Configurable driver preferences | Different environments may have different normal baselines; hard-coded thresholds would require code changes to tune |
