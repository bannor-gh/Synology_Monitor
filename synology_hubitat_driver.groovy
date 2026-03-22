metadata {
    definition(name: "Synology NAS Monitor", namespace: "yourNamespace", author: "Your Name") {
        capability "Refresh"
        capability "Sensor"
        command "fetchSynologyData"

        // Core numeric attributes (usable in Rule Machine / dashboard tiles)
        attribute "cpuPercent",       "number"
        attribute "loadAvg1min",      "number"
        attribute "loadAvg5min",      "number"
        attribute "memoryPercent",    "number"
        attribute "memoryUsedMB",     "number"
        attribute "memoryTotalMB",    "number"
        attribute "swapPercent",      "number"
        attribute "uptimeDays",       "number"
        attribute "volume1Path",      "string"
        attribute "volume1UsedGB",    "number"
        attribute "volume1TotalGB",   "number"
        attribute "volume1Percent",   "number"
        attribute "volume2Path",      "string"
        attribute "volume2UsedGB",    "number"
        attribute "volume2TotalGB",   "number"
        attribute "volume2Percent",   "number"
        attribute "containersTotal",  "number"
        attribute "containersRunning","number"
        attribute "lastUpdate",       "string"

        // Dashboard tiles
        attribute "nasSummary",       "string"   // compact tile (existing)
        attribute "expandedSummary",  "string"   // full health tile (new)
    }
}

preferences {
    input name: "flaskBaseUrl",    type: "text",   title: "Flask API Base URL (e.g., http://192.168.10.175:5051)", required: true
    input name: "refreshInterval", type: "number", title: "Refresh interval (minutes)", defaultValue: 5, required: true
    input name: "cpuWarn",         type: "number", title: "CPU warn (%)",       defaultValue: 50, required: true
    input name: "cpuCrit",         type: "number", title: "CPU critical (%)",   defaultValue: 80, required: true
    input name: "memWarn",         type: "number", title: "RAM warn (%)",       defaultValue: 70, required: true
    input name: "memCrit",         type: "number", title: "RAM critical (%)",   defaultValue: 85, required: true
    input name: "swapWarn",        type: "number", title: "Swap warn (%)",      defaultValue: 50, required: true
    input name: "swapCrit",        type: "number", title: "Swap critical (%)",  defaultValue: 80, required: true
    input name: "diskWarn",        type: "number", title: "Disk warn (%)",      defaultValue: 70, required: true
    input name: "diskCrit",        type: "number", title: "Disk critical (%)",  defaultValue: 85, required: true
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
                parseSynologyData(resp.data)
            } else {
                log.error "Failed to fetch Synology data: HTTP ${resp.status}"
            }
        }
    } catch (e) {
        log.error "Error fetching Synology data: ${e.message}"
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private String dot(BigDecimal value, BigDecimal warn, BigDecimal crit) {
    return value >= crit ? "🔴" : value >= warn ? "🟡" : "🟢"
}

private String dotContainers(Integer running, Integer total) {
    if (running == null || total == null) return "⚪"
    return (running == 0) ? "🔴" : (running < total) ? "🟡" : "🟢"
}

private String dotRaid(raids) {
    if (!raids || raids.size() == 0) return "⚪"
    if (raids.any { it.health == "degraded" })   return "🔴"
    if (raids.any { it.health == "recovering" }) return "🟡"
    if (raids.any { it.health == "unknown" })    return "🟡"
    return "🟢"
}

private String dotSmart(drives) {
    if (!drives || drives.size() == 0) return "⚪"
    if (drives.any { it.health == "fail" })    return "🔴"
    if (drives.any { it.health == "unknown" }) return "🟡"
    return "🟢"
}

// ---------------------------------------------------------------------------
// Parse + publish
// ---------------------------------------------------------------------------

private void parseSynologyData(json) {
    // CPU
    if (json.cpu) sendEvent(name: "cpuPercent", value: json.cpu.percent, unit: "%")

    // Load average
    def load = json.load_average
    if (load) {
        sendEvent(name: "loadAvg1min", value: load["1min"])
        sendEvent(name: "loadAvg5min", value: load["5min"])
    }

    // Memory & Swap
    def mem = json.memory
    if (mem) {
        sendEvent(name: "memoryPercent", value: mem.percent,       unit: "%")
        sendEvent(name: "memoryUsedMB",  value: mem.used_mb,       unit: "MB")
        sendEvent(name: "memoryTotalMB", value: mem.total_mb,      unit: "MB")
        sendEvent(name: "swapPercent",   value: mem.swap_percent,  unit: "%")
    }

    // Uptime
    def up = json.uptime
    if (up) sendEvent(name: "uptimeDays", value: up.days)

    // Storage — publish up to two volumes
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

    // Docker containers
    def dkr = json.docker
    if (dkr) {
        if (dkr.total   != null) sendEvent(name: "containersTotal",   value: dkr.total)
        if (dkr.running != null) sendEvent(name: "containersRunning", value: dkr.running)
    }

    // Timestamp  (formatted for tile display)
    def ts = json.timestamp ?: "unknown"
    try {
        def dateObj = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", ts)
        ts = dateObj.format("MM/dd HH:mm", location.timeZone)
    } catch (e) {
        log.warn "Could not parse timestamp: ${ts}"
    }
    sendEvent(name: "lastUpdate", value: ts)

    // Threshold values used by both tiles
    def cW = (cpuWarn  ?: 50) as BigDecimal;  def cC = (cpuCrit  ?: 80) as BigDecimal
    def mW = (memWarn  ?: 70) as BigDecimal;  def mC = (memCrit  ?: 85) as BigDecimal
    def sW = (swapWarn ?: 50) as BigDecimal;  def sC = (swapCrit ?: 80) as BigDecimal
    def dW = (diskWarn ?: 70) as BigDecimal;  def dC = (diskCrit ?: 85) as BigDecimal

    def cpuVal  = (json.cpu?.percent       ?: 0) as BigDecimal
    def memVal  = (json.memory?.percent    ?: 0) as BigDecimal
    def swapVal = (json.memory?.swap_percent ?: 0) as BigDecimal

    def dkrTotal   = dkr?.total   != null ? dkr.total   as Integer : null
    def dkrRunning = dkr?.running != null ? dkr.running as Integer : null

    // -----------------------------------------------------------------------
    // nasSummary — compact tile (unchanged behaviour)
    // -----------------------------------------------------------------------
    try {
        def memUsed = json.memory?.used_mb  ?: 0
        def memTot  = json.memory?.total_mb ?: 0

        def lines = "<div style=\"font-size:0.9em;\">"
        lines += "${dot(cpuVal, cW, cC)} CPU: ${cpuVal}%<br>"
        lines += "${dot(memVal, mW, mC)} RAM: ${memVal}%<br>"
        volumes?.each { vol ->
            def pct = (vol.percent ?: 0) as BigDecimal
            lines += "${dot(pct, dW, dC)} ${vol.path}: ${pct}%<br>"
        }
        def dkrLabel = (dkrTotal != null) ? "${dkrRunning} of ${dkrTotal}" : "n/a"
        lines += "${dotContainers(dkrRunning, dkrTotal)} Cntr: ${dkrLabel}<br>"
        lines += "🕒 ${ts}</div>"
        sendEvent(name: "nasSummary", value: lines)
    } catch (e) {
        log.warn "Failed to build nasSummary: ${e.message}"
    }

    // -----------------------------------------------------------------------
    // expandedSummary — full health tile
    // -----------------------------------------------------------------------
    try {
        def hr   = "<hr style=\"margin:3px 0; border:none; border-top:1px solid rgba(128,128,128,0.4);\">"
        def load1 = json.load_average?.getAt("1min") ?: 0
        def load5 = json.load_average?.getAt("5min") ?: 0
        def memUsed  = json.memory?.used_mb       ?: 0
        def memTot   = json.memory?.total_mb      ?: 0
        def swapUsed = json.memory?.swap_used_mb  ?: 0
        def swapTot  = json.memory?.swap_total_mb ?: 0

        def s = "<div style=\"font-size:0.85em; line-height:1.6;\">"

        // Compute
        s += "${dot(cpuVal,cW,cC)} CPU ${cpuVal}%  load ${load1}/${load5}<br>"
        s += "${dot(memVal,mW,mC)} RAM ${memVal}%  ${memUsed}/${memTot} MB<br>"
        s += "${dot(swapVal,sW,sC)} Swap ${swapVal}%  ${swapUsed}/${swapTot} MB<br>"
        s += hr

        // Network
        def nets = json.network
        if (nets && nets.size() > 0) {
            nets.each { iface ->
                s += "🔵 ${iface.interface}  ↓${iface.rx_mbs}  ↑${iface.tx_mbs} MB/s<br>"
            }
            s += hr
        }

        // Storage
        volumes?.each { vol ->
            def pct = (vol.percent ?: 0) as BigDecimal
            s += "${dot(pct,dW,dC)} ${vol.path}  ${pct}%  ${vol.used_gb}/${vol.total_gb} GB<br>"
        }
        s += hr

        // RAID
        def raids = json.raid
        if (raids && raids.size() > 0) {
            raids.each { arr ->
                def rdot = arr.health == "clean" ? "🟢" : arr.health == "recovering" ? "🟡" : "🔴"
                s += "${rdot} RAID ${arr.name}  ${arr.health}<br>"
            }
        } else {
            s += "⚪ RAID  n/a<br>"
        }

        // SMART
        def smarts = json.smart
        if (smarts && smarts.size() > 0) {
            def failed  = smarts.count { it.health == "fail" }
            def passing = smarts.count { it.health == "pass" }
            def smartLabel = failed > 0 ? "${failed} FAILED" : "${passing} of ${smarts.size()} pass"
            s += "${dotSmart(smarts)} SMART  ${smartLabel}<br>"
        } else {
            s += "⚪ SMART  n/a<br>"
        }
        s += hr

        // Containers
        s += "${dotContainers(dkrRunning, dkrTotal)} Cntr  ${dkrRunning ?: '?'} of ${dkrTotal ?: '?'}<br>"
        def stopped = dkr?.containers?.findAll { !it.running }?.collect { it.name }
        if (stopped && stopped.size() > 0) {
            s += "<span style=\"opacity:0.65; padding-left:1.2em;\">↳ ${stopped.join(', ')}</span><br>"
        }
        s += hr

        // Uptime + timestamp
        def uptimeStr = up ? "${up.days}d ${up.hours}h" : "?"
        s += "<span style=\"opacity:0.6;\">Up ${uptimeStr}  |  ${ts}</span>"
        s += "</div>"

        sendEvent(name: "expandedSummary", value: s)
    } catch (e) {
        log.warn "Failed to build expandedSummary: ${e.message}"
    }
}
