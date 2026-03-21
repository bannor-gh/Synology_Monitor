# Hubitat Integration — Method & Auto-Deploy

This document explains how a Groovy driver is deployed to a Hubitat Elevation hub
programmatically from a GitHub Actions CI pipeline, with no manual copy/paste required
after the initial install.

---

## Overview

Hubitat has no official API for managing driver code, but its web UI communicates
with the hub over a well-known set of local HTTP endpoints that can be scripted.
The approach:

1. Optionally authenticate (most local hubs don't require it)
2. Find the existing driver by name to get its ID
3. Fetch the current version number (required by the hub as a concurrency guard)
4. POST the updated source code

All traffic is local — the script runs on the NAS (same LAN as the hub) via a
self-hosted GitHub Actions runner, so the hub's IP is reachable directly.

---

## Hubitat Local HTTP Endpoints

These endpoints are unauthenticated by default on most local Hubitat setups.
If the hub has authentication enabled, a session cookie from a login POST is required.

### Check if authentication is needed

```
GET http://<HUB_IP>/driver/list/data
```

If the response redirects to `/login`, auth is required. If it returns JSON directly,
no auth is needed.

### Login (only if auth is required)

```
POST http://<HUB_IP>/login
Content-Type: application/x-www-form-urlencoded

username=<user>&password=<pass>&submit=Login
```

Save the session cookie. All subsequent requests must include it.
A redirect back to `/login` means credentials were wrong.

### List all user drivers (to find the driver ID)

```
GET http://<HUB_IP>/driver/list/data
```

Returns a JSON array. Filter for `"type": "usr"` to exclude built-in drivers.
Match on the `"name"` field (must match the `name` in the driver's `definition()`).

Example response entry:
```json
{ "id": 123, "name": "Synology NAS Monitor", "type": "usr" }
```

### Fetch current driver version

```
GET http://<HUB_IP>/driver/ajax/code?id=<ID>
```

Returns:
```json
{ "status": "success", "id": 123, "version": 7, "source": "..." }
```

The `version` field **must** be sent back with the update — the hub uses it as
an optimistic concurrency token and will reject updates with a stale version.

### Update driver source

```
POST http://<HUB_IP>/driver/ajax/update
Content-Type: application/x-www-form-urlencoded

id=<ID>&version=<VERSION>&source=<GROOVY SOURCE>
```

Success response:
```json
{ "status": "success", "id": 123, "version": 8 }
```

Failure response (includes Groovy compile errors with line numbers):
```json
{ "status": "error", "errorMessage": "..." }
```

---

## deploy_hubitat.py

A self-contained Python script that implements the above flow.

```python
#!/usr/bin/env python3
"""
deploy_hubitat.py — Push updated Groovy driver code to a Hubitat hub.

Usage:
    python3 deploy_hubitat.py <driver_file.groovy>

Required environment variables:
    HUBITAT_IP    — Hub IP or hostname (e.g. 192.168.10.100)
    HUBITAT_USER  — Hub admin username (only used if hub requires auth)
    HUBITAT_PASS  — Hub admin password (only used if hub requires auth)
"""

import os, sys, requests

DRIVER_NAME = "Synology NAS Monitor"  # Must match definition(name: "...") in the .groovy file


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

**Dependency:** `pip install requests`

---

## GitHub Actions Integration

The script is called as a step in `.github/workflows/deploy.yml` after the NAS
file sync. Hub credentials are stored as GitHub repository secrets — never committed.

```yaml
- name: Deploy driver to Hubitat
  env:
    HUBITAT_IP:   ${{ secrets.HUBITAT_IP }}
    HUBITAT_USER: ${{ secrets.HUBITAT_USER }}
    HUBITAT_PASS: ${{ secrets.HUBITAT_PASS }}
  run: |
    pip3 install requests --quiet
    python3 /synology-monitor/deploy_hubitat.py /synology-monitor/synology_hubitat_driver.groovy
```

The runner is self-hosted on the NAS (same LAN as the Hubitat hub), so
`http://<HUB_IP>` is reachable without any port forwarding or cloud relay.

---

## Dashboard Tile — nasSummary

Hubitat's built-in dashboard **Attribute** tile renders plain text and `<br>` line
breaks but strips `<span>` tags and inline styles. The driver's `nasSummary` attribute
is built accordingly — emoji dots instead of colored HTML spans:

```groovy
def summary = "${dot(cpuVal, cW, cC)} CPU: ${cpuVal}%<br>" +
              "${dot(memVal, mW, mC)} RAM: ${memVal}%<br>" +
              "${dot(v1pct,  dW, dC)} ${v1path}: ${v1pct}%<br>" +
              "${dot(v2pct,  dW, dC)} ${v2path}: ${v2pct}%<br>" +
              "🕒 ${ts}"
sendEvent(name: "nasSummary", value: summary)

private String dot(BigDecimal value, BigDecimal warn, BigDecimal crit) {
    if (value >= crit) return "🔴"
    if (value >= warn) return "🟡"
    return "🟢"
}
```

**Key lessons learned:**
- HTML `<table>` and `<span style="...">` are silently stripped by the dashboard tile renderer
- `<br>` line breaks do render correctly
- Emoji (🟢🟡🔴) render reliably as status indicators
- The attribute must be declared with `capability "Sensor"` for it to appear in the dashboard picker
- If a tile shows "please select an attribute" after binding, drop the device and re-add it — Hubitat sometimes caches a stale state

---

## One-Time Manual Steps

Auto-deploy handles all subsequent updates, but the very first install requires
two manual steps:

1. **Install the driver** — Hubitat → Drivers Code → New Driver → paste `.groovy` → Save
2. **Create the device** — Devices → Add Virtual Device → select the driver → configure preferences

After that, every `git push` to `main` automatically updates the driver on the hub.

---

## Caveats

- These Hubitat endpoints are **undocumented and reverse-engineered** from the web UI.
  They could change in a future hub firmware update without notice.
- The `version` field in the update call is mandatory — always fetch it fresh before posting.
- `DRIVER_NAME` in the script must match the `name` field in the Groovy `definition()` exactly (case-sensitive).
- The script handles update only — creating a brand-new driver via API requires parsing
  an HTML response from `/driver/save`, which is more complex. First install stays manual.
