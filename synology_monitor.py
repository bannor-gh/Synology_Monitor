#!/usr/bin/env python3
"""
synology_monitor.py - Collect Synology NAS system metrics.

Reads CPU, memory, swap, uptime, network throughput, storage, Docker container
state, RAID status, and drive SMART health, then saves to synology_data.json
for the Flask API to serve.

Run via Synology Task Scheduler (e.g. every 5 minutes, as root).
"""

import json
import time
import os
import subprocess
import logging
from logging.handlers import RotatingFileHandler
from datetime import datetime, timezone

BASE_DIR    = "/volume1/Synology Monitor"
OUTPUT_FILE = os.path.join(BASE_DIR, "synology_data.json")
LOG_FILE    = os.path.join(BASE_DIR, "synology_monitor.log")

handler = RotatingFileHandler(LOG_FILE, maxBytes=2 * 1024 * 1024, backupCount=3)
logging.basicConfig(
    handlers=[handler],
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# CPU  (split into pre/post reads so we can share the sleep with network)
# ---------------------------------------------------------------------------

def _read_cpu_stat():
    """Return (idle_jiffies, total_jiffies) from /proc/stat."""
    with open("/proc/stat") as f:
        line = f.readline()
    fields = list(map(int, line.split()[1:]))
    return fields[3], sum(fields)


def _calc_cpu_percent(pre, post):
    idle1, total1 = pre
    idle2, total2 = post
    delta_total = total2 - total1
    if delta_total == 0:
        return 0.0
    return round((1.0 - (idle2 - idle1) / delta_total) * 100, 1)


# ---------------------------------------------------------------------------
# Network throughput  (pre/post reads share the same sleep as CPU)
# ---------------------------------------------------------------------------

# Interfaces to skip — loopback, Docker bridge, and container virtual nics
_SKIP_IFACE_EXACT  = {"lo", "docker0"}
_SKIP_IFACE_PREFIX = ("br-", "veth")


def _read_net_dev():
    """Return {iface: (rx_bytes, tx_bytes)} from /proc/net/dev."""
    stats = {}
    with open("/proc/net/dev") as f:
        for line in f:
            line = line.strip()
            if ":" not in line:
                continue
            iface, data = line.split(":", 1)
            iface = iface.strip()
            if iface in _SKIP_IFACE_EXACT:
                continue
            if any(iface.startswith(p) for p in _SKIP_IFACE_PREFIX):
                continue
            fields = data.split()
            stats[iface] = (int(fields[0]), int(fields[8]))   # rx_bytes, tx_bytes
    return stats


def _calc_network(pre, post, interval):
    """Return list of {interface, rx_mbs, tx_mbs}."""
    result = []
    for iface, (rx1, tx1) in pre.items():
        if iface not in post:
            continue
        rx2, tx2 = post[iface]
        result.append({
            "interface": iface,
            "rx_mbs":    max(0.0, round((rx2 - rx1) / interval / (1024 * 1024), 2)),
            "tx_mbs":    max(0.0, round((tx2 - tx1) / interval / (1024 * 1024), 2)),
        })
    return result


# ---------------------------------------------------------------------------
# Memory & Swap
# ---------------------------------------------------------------------------

def get_memory_info():
    """Parse /proc/meminfo for RAM and swap stats."""
    mem = {}
    with open("/proc/meminfo") as f:
        for line in f:
            parts = line.split()
            if len(parts) >= 2:
                mem[parts[0].rstrip(":")] = int(parts[1])   # kB

    total     = mem.get("MemTotal", 0)
    available = mem.get("MemAvailable", mem.get("MemFree", 0))
    used      = total - available

    swap_total = mem.get("SwapTotal", 0)
    swap_free  = mem.get("SwapFree",  0)
    swap_used  = swap_total - swap_free

    return {
        "total_mb":      round(total     / 1024, 1),
        "used_mb":       round(used      / 1024, 1),
        "available_mb":  round(available / 1024, 1),
        "percent":       round(used      / total      * 100, 1) if total      > 0 else 0.0,
        "swap_total_mb": round(swap_total / 1024, 1),
        "swap_used_mb":  round(swap_used  / 1024, 1),
        "swap_percent":  round(swap_used  / swap_total * 100, 1) if swap_total > 0 else 0.0,
    }


# ---------------------------------------------------------------------------
# Load average
# ---------------------------------------------------------------------------

def get_load_average():
    """Return 1-, 5-, and 15-minute load averages from /proc/loadavg."""
    with open("/proc/loadavg") as f:
        parts = f.read().split()
    return {"1min": float(parts[0]), "5min": float(parts[1]), "15min": float(parts[2])}


# ---------------------------------------------------------------------------
# Uptime
# ---------------------------------------------------------------------------

def get_uptime():
    """Return system uptime broken into days/hours/minutes."""
    with open("/proc/uptime") as f:
        seconds = float(f.read().split()[0])
    s = int(seconds)
    return {
        "total_seconds": s,
        "days":    s // 86400,
        "hours":   (s % 86400) // 3600,
        "minutes": (s % 3600)  // 60,
    }


# ---------------------------------------------------------------------------
# Storage
# ---------------------------------------------------------------------------

def get_storage_info():
    """Return disk usage for each Synology volume found on the system."""
    candidate_paths = ["/volume1", "/volume2", "/volume3"]
    volumes = []
    seen_totals = set()

    for path in candidate_paths:
        if not os.path.exists(path):
            continue
        try:
            stat  = os.statvfs(path)
            total = stat.f_blocks * stat.f_frsize
            free  = stat.f_bavail * stat.f_frsize
            used  = total - free
            if total == 0 or total in seen_totals:
                continue
            seen_totals.add(total)
            volumes.append({
                "path":     path,
                "total_gb": round(total / (1024 ** 3), 2),
                "used_gb":  round(used  / (1024 ** 3), 2),
                "free_gb":  round(free  / (1024 ** 3), 2),
                "percent":  round(used / total * 100,  1),
            })
        except OSError as e:
            log.warning("Could not stat %s: %s", path, e)

    return volumes


# ---------------------------------------------------------------------------
# Docker containers
# ---------------------------------------------------------------------------

def get_docker_info():
    """Return total/running counts and per-container name+state via docker CLI."""
    try:
        result = subprocess.run(
            ["docker", "ps", "-a", "--format", "{{.Names}}\t{{.Status}}"],
            capture_output=True, text=True, timeout=10,
        )
        containers = []
        for line in result.stdout.strip().splitlines():
            if not line:
                continue
            parts   = line.split("\t", 1)
            name    = parts[0]
            status  = parts[1] if len(parts) > 1 else ""
            running = status.lower().startswith("up")
            containers.append({"name": name, "status": status, "running": running})

        total   = len(containers)
        running = sum(1 for c in containers if c["running"])
        return {"total": total, "running": running, "containers": containers}
    except Exception as e:
        log.warning("Could not query Docker: %s", e)
        return {"total": None, "running": None, "containers": []}


# ---------------------------------------------------------------------------
# RAID  (/proc/mdstat)
# ---------------------------------------------------------------------------

def get_raid_status():
    """Return health status for each MD RAID array found in /proc/mdstat."""
    try:
        with open("/proc/mdstat") as f:
            lines = f.read().splitlines()
        arrays = []
        for i, line in enumerate(lines):
            if not line.startswith("md"):
                continue
            name      = line.split(":")[0].strip()
            next_line = lines[i + 1] if i + 1 < len(lines) else ""
            if "degraded" in line:
                health = "degraded"
            elif "recovering" in next_line or "resync" in next_line:
                health = "recovering"
            elif "active" in line:
                health = "clean"
            else:
                health = "unknown"
            arrays.append({"name": name, "health": health})
        return arrays
    except FileNotFoundError:
        return []
    except Exception as e:
        log.warning("Could not read /proc/mdstat: %s", e)
        return []


# ---------------------------------------------------------------------------
# SMART drive health
# ---------------------------------------------------------------------------

def get_smart_status():
    """Check SMART overall health for each /dev/sdX via smartctl (requires root)."""
    drives = []
    for letter in "abcdefghijklmnop":
        dev = f"/dev/sd{letter}"
        if not os.path.exists(dev):
            break
        try:
            result = subprocess.run(
                ["smartctl", "-H", dev],
                capture_output=True, text=True, timeout=10,
            )
            output = result.stdout
            if "PASSED" in output:
                health = "pass"
            elif "FAILED" in output:
                health = "fail"
            else:
                health = "unknown"
            drives.append({"device": dev, "health": health})
        except FileNotFoundError:
            log.warning("smartctl not found — skipping SMART checks")
            break
        except Exception as e:
            log.warning("SMART check failed for %s: %s", dev, e)

    return drives


# ---------------------------------------------------------------------------
# Main collection
# ---------------------------------------------------------------------------

def collect_metrics():
    log.info("Collecting Synology metrics...")

    # Read network and CPU pre-samples, sleep once, then read post-samples.
    # This gives accurate deltas for both metrics with only a single 1-second pause.
    net_pre = _read_net_dev()
    cpu_pre = _read_cpu_stat()
    time.sleep(1.0)
    net_post = _read_net_dev()
    cpu_post = _read_cpu_stat()

    cpu_percent = _calc_cpu_percent(cpu_pre, cpu_post)
    network     = _calc_network(net_pre, net_post, 1.0)
    memory      = get_memory_info()
    load        = get_load_average()
    uptime      = get_uptime()
    storage     = get_storage_info()
    docker      = get_docker_info()
    raid        = get_raid_status()
    smart       = get_smart_status()

    data = {
        "timestamp":    datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S+00:00"),
        "cpu":          {"percent": cpu_percent},
        "load_average": load,
        "memory":       memory,
        "uptime":       uptime,
        "network":      network,
        "storage":      storage,
        "docker":       docker,
        "raid":         raid,
        "smart":        smart,
    }

    with open(OUTPUT_FILE, "w") as f:
        json.dump(data, f, indent=2)

    log.info(
        "Metrics saved — CPU: %s%%, RAM: %s%%, Swap: %s%%, "
        "Volumes: %d, Containers: %s/%s, RAID arrays: %d, Drives: %d",
        cpu_percent, memory["percent"], memory["swap_percent"],
        len(storage), docker.get("running"), docker.get("total"),
        len(raid), len(smart),
    )
    return data


if __name__ == "__main__":
    collect_metrics()
