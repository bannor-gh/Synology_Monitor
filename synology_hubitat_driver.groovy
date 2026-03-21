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

private String dot(BigDecimal value, BigDecimal warn, BigDecimal crit) {
    def color = value >= crit ? "#e74c3c" : value >= warn ? "#f39c12" : "#2ecc71"
    return "<span style=\"color:${color}; font-size:1.2em;\">&#11044;</span>"
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
        ts = dateObj.format("MM/dd HH:mm", location.timeZone)
    } catch (e) {
        log.warn "Could not parse timestamp: ${ts}"
    }
    sendEvent(name: "lastUpdate", value: ts)

    // Summary tile with colored status dots
    try {
        def cpuVal  = (json.cpu?.percent  ?: 0) as BigDecimal
        def memVal  = (json.memory?.percent ?: 0) as BigDecimal
        def memUsed = json.memory?.used_mb  ?: 0
        def memTot  = json.memory?.total_mb ?: 0

        def cW = (cpuWarn  ?: 50) as BigDecimal
        def cC = (cpuCrit  ?: 80) as BigDecimal
        def mW = (memWarn  ?: 70) as BigDecimal
        def mC = (memCrit  ?: 85) as BigDecimal
        def dW = (diskWarn ?: 70) as BigDecimal
        def dC = (diskCrit ?: 85) as BigDecimal

        def lines = "${dot(cpuVal, cW, cC)} CPU: ${cpuVal}%<br>" +
                    "${dot(memVal, mW, mC)} RAM: ${memVal}% (${memUsed}/${memTot} MB)<br>"

        volumes?.each { vol ->
            def pct = (vol.percent ?: 0) as BigDecimal
            lines += "${dot(pct, dW, dC)} ${vol.path}: ${pct}% (${vol.used_gb}/${vol.total_gb} GB)<br>"
        }

        lines += "🕒 ${ts}"

        sendEvent(name: "systemSummary", value: lines)
    } catch (e) {
        log.warn "Failed to build systemSummary: ${e.message}"
    }
}
