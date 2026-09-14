"""Verify the local provisioned dashboard after the opt-in Java Influx test writes a sample."""
import base64
import json
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parent
settings = dict(line.split("=", 1) for line in (root / ".env").read_text().splitlines()
                if line and not line.startswith("#"))
authorization = base64.b64encode(("heist:" + settings["GRAFANA_PASSWORD"]).encode()).decode()


def request(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request("http://127.0.0.1:13000" + path, data=data,
                                 headers={"Authorization": "Basic " + authorization,
                                          "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)


health = request("/api/datasources/uid/heist-influx/health")
assert health["status"] == "OK", "Grafana could not reach its InfluxDB datasource"
dashboard = request("/api/dashboards/uid/heist-overview")
assert dashboard["meta"]["provisioned"], "Dashboard was not provisioned from repository files"
for panel in dashboard["dashboard"]["panels"]:
    target = dict(panel["targets"][0], intervalMs=5000, maxDataPoints=600)
    response = request("/api/ds/query", {"queries": [target], "from": "now-15m", "to": "now"})
    result = response["results"]["A"]
    assert result.get("status", 200) == 200 and not result.get("error"), panel["title"] + " query failed"
    assert any(frame.get("data", {}).get("values", [[]])[0] for frame in result.get("frames", [])), \
        panel["title"] + " has no samples; run the Java Influx integration test first"
    print("Verified:", panel["title"])
print("Provisioned datasource and all dashboard queries passed; samples are synthetic test data.")
