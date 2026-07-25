import json

def create_panel(title, expr, ptype="timeseries", x=0, y=0, w=12, h=8, unit="short", format="time_series"):
    return {
        "title": title,
        "type": ptype,
        "gridPos": {"x": x, "y": y, "w": w, "h": h},
        "targets": [{"expr": expr, "format": format}],
        "fieldConfig": {"defaults": {"unit": unit}}
    }

def create_stat(title, expr, x, y, w, h, unit="short"):
    return create_panel(title, expr, "stat", x, y, w, h, unit)

panels = [
    # Top Row - Basic Stats
    create_stat("System CPU Usage", "sum(system_cpu_usage)", 0, 0, 3, 4, "percentunit"),
    create_stat("Process Uptime", "process_uptime_seconds", 3, 0, 3, 4, "s"),
    create_stat("Open Files", "process_files_open_instances", 6, 0, 3, 4, "short"),
    create_stat("Live WebSockets", "sum(tomcat_websocket_connections_active_current)", 9, 0, 3, 4, "short"),

    # Row 2 - JVM Memory
    create_panel("JVM Heap Memory", "jvm_memory_used_bytes{area=\"heap\"}", "timeseries", 0, 4, 12, 8, "bytes"),
    create_panel("JVM Non-Heap Memory", "jvm_memory_used_bytes{area=\"nonheap\"}", "timeseries", 12, 4, 12, 8, "bytes"),

    # Row 3 - JVM Threads & GC
    create_panel("JVM Threads", "jvm_threads_live_threads", "timeseries", 0, 12, 8, 8, "short"),
    create_panel("GC Pauses", "rate(jvm_gc_pause_seconds_sum[1m])", "timeseries", 8, 12, 8, 8, "s"),
    create_panel("Log Events (Errors)", "rate(logback_events_total{level=\"error\"}[1m])", "timeseries", 16, 12, 8, 8, "short"),

    # Row 4 - HTTP & WebSockets
    create_panel("HTTP Requests Per Second", "rate(http_server_requests_seconds_count[1m])", "timeseries", 0, 20, 12, 8, "reqps"),
    create_panel("HTTP Latency", "rate(http_server_requests_seconds_sum[1m]) / rate(http_server_requests_seconds_count[1m])", "timeseries", 12, 20, 12, 8, "s"),

    # Row 5 - Redis & Postgres
    create_panel("Redis Operations / Sec", "rate(redis_commands_processed_total[1m])", "timeseries", 0, 28, 12, 8, "reqps"),
    create_panel("Postgres Active Connections", "pg_stat_activity_count{state=\"active\"}", "timeseries", 12, 28, 12, 8, "short")
]

dashboard = {
    "title": "ApexBid Ultimate Monitoring",
    "uid": "apexbid-ultimate",
    "timezone": "browser",
    "schemaVersion": 30,
    "refresh": "5s",
    "panels": panels
}

with open('grafana/provisioning/dashboards/apexbid_ultimate.json', 'w') as f:
    json.dump(dashboard, f, indent=2)
