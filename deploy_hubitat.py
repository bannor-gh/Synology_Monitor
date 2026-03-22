#!/usr/bin/env python3
"""
deploy_hubitat.py — Push updated Groovy driver code to a Hubitat hub.

Usage:
    python3 deploy_hubitat.py <driver_file.groovy>

Required environment variables:
    HUBITAT_IP    — Hub IP or hostname (e.g. 192.168.10.100)
    HUBITAT_USER  — Hub admin username
    HUBITAT_PASS  — Hub admin password
"""

import os
import sys
import requests

DRIVER_NAME = "Synology NAS Monitor"


def needs_auth(session, base_url):
    """Return True if the hub redirects to /login without a session."""
    resp = session.get(f"{base_url}/driver/list/data", allow_redirects=True, timeout=15)
    return "/login" in resp.url or resp.status_code == 401


def login(session, base_url, user, password):
    if not needs_auth(session, base_url):
        print("Hub requires no authentication — skipping login.")
        return

    resp = session.post(
        f"{base_url}/login",
        data={"username": user, "password": password, "submit": "Login"},
        allow_redirects=True,
        timeout=15,
    )
    resp.raise_for_status()
    # Confirm we're no longer on the login page
    if "/login" in resp.url:
        raise RuntimeError("Hubitat login failed — check HUBITAT_USER / HUBITAT_PASS")
    print("Logged in to Hubitat.")


def find_driver_id(session, base_url, name):
    resp = session.get(f"{base_url}/driver/list/data", timeout=15)
    resp.raise_for_status()
    drivers = resp.json()
    for d in drivers:
        if d.get("name") == name and d.get("type") == "usr":
            return d["id"]
    raise RuntimeError(
        f"Driver '{name}' not found on hub. Install it manually once, then re-run."
    )


def get_current_version(session, base_url, driver_id):
    resp = session.get(f"{base_url}/driver/ajax/code", params={"id": driver_id}, timeout=15)
    resp.raise_for_status()
    data = resp.json()
    if data.get("status") != "success":
        raise RuntimeError(f"Failed to fetch driver version: {data}")
    return data["version"]


def update_driver(session, base_url, driver_id, version, source):
    resp = session.post(
        f"{base_url}/driver/ajax/update",
        data={"id": driver_id, "version": version, "source": source},
        timeout=30,
    )
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

    with open(groovy_file, "r") as f:
        source = f.read()

    base_url = f"http://{hub_ip}"
    session = requests.Session()

    login(session, base_url, hub_user, hub_pass)
    driver_id = find_driver_id(session, base_url, DRIVER_NAME)
    print(f"Found driver '{DRIVER_NAME}' — id={driver_id}")
    version = get_current_version(session, base_url, driver_id)
    print(f"Current version: {version}")
    update_driver(session, base_url, driver_id, version, source)
    print("Hubitat driver deploy complete.")


if __name__ == "__main__":
    main()
