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
    input name: "flaskBaseUrl", type: "text", title: "Flask API Base URL (e.g., http://192.168.10.175:5051)", required: true
    input name: "refreshInterval", type: "number", title: "Refresh interval (minutes)", defaultValue: 5, required: true
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

    // Summary tile
    try {
        def cpuVal  = json.cpu?.percent ?: 0
        def memVal  = json.memory?.percent ?: 0
        def memUsed = json.memory?.used_mb ?: 0
        def memTot  = json.memory?.total_mb ?: 0
        def v1      = json.storage?.size() > 0 ? json.storage[0] : null
        def diskLine = v1 ? "${v1.path}: ${v1.used_gb} / ${v1.total_gb} GB (${v1.percent}%)" : "N/A"

        def summary = "CPU: ${cpuVal}%<br>" +
                      "RAM: ${memUsed} / ${memTot} MB (${memVal}%)<br>" +
                      "Disk ${diskLine}<br>" +
                      "Updated: ${ts}"
        sendEvent(name: "systemSummary", value: summary)
    } catch (e) {
        log.warn "Failed to build systemSummary: ${e.message}"
    }
}
