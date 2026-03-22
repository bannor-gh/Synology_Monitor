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
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "cpu": {
            "percent": cpu_percent,
        },
        "load_average": load,
        "memory": memory,
        "storage": storage,
        "docker": docker,
    }

    with open(OUTPUT_FILE, "w") as f:
        json.dump(data, f, indent=2)

    log.info(
        "Metrics saved — CPU: %s%%, MEM: %s%%, Volumes: %d, Containers: %s/%s",
        cpu_percent,
        memory["percent"],
        len(storage),
        docker.get("running"),
        docker.get("total"),
    )
    return data


if __name__ == "__main__":
    collect_metrics()
