# InfluxDB and Grafana

The plugin now exports numeric operational samples every five seconds. Enable `metrics.enabled` in the plugin configuration and supply `HEIST_INFLUX_TOKEN` in the server process environment. Configure the InfluxDB origin URL, organization, bucket, and a stable `metrics.server-id` unique to each concurrently running server. `HEIST_SERVER_ID` overrides that ID when supplied, as in the pod template. Never reuse player IDs or match UUIDs as server tags. The default URL targets the local fixture on port 18086.

The runtime uses Java's HTTP client and the InfluxDB 2 [write API](https://docs.influxdata.com/influxdb/v2/api/write/), with millisecond timestamps. Only HTTP 204 counts as acknowledgement. Redirects are disabled to avoid forwarding authorization to another endpoint. No third-party metrics dependency is added.

## Collected values

| Fields | Meaning |
|---|---|
| `tps_1m`, `server_tick_ms` | Paper's one-minute TPS estimate, capped at 20, and its average server tick duration |
| `heist_tick_mean_ms`, `heist_tick_max_ms` | Mean/maximum GameHeist update-loop duration in the sample window; not whole-server MSPT or percentiles |
| `online_players`, `crew_players` | Online server players and current instance roster membership |
| `active_instances`, `loud_instances` | Current instances, including finalization/cleanup, and instances whose alarm is loud |
| `instances_ready/running/finalizing/closing` | Counts by runtime lifecycle state |
| `pending_profile_operations`, `unacknowledged_profile_writes` | Profile load/save work and saves without confirmed acknowledgement |
| `draining`, `safe_to_stop`, `world_cleanup_healthy` | Drain status flags represented as 0/1 |
| `export_delivered_total/failed_total/dropped_total` | Process-local cumulative exporter outcomes; reset at restart |

The sole measurement is `heist_server` and its sole tag is the configured server ID. No player names, UUIDs, IPs, match IDs, or free-form error messages are exported. Fields have fixed numeric types and snapshots have a hard 64-field limit.

## Failure behavior

The owning game thread copies a small immutable snapshot. A daemon worker performs HTTP delivery with a two-second connection timeout and three-second request timeout. There is at most one active request and one queued sample. A new sample replaces an older queued sample when the sink is slow. Failed samples are counted and discarded; the next periodic sample can recover automatically. Warning logs are limited to one per minute.

This is intentionally lossy monitoring, not a durable gameplay event stream. It has no influence on profile persistence, match results, admission, or drain acknowledgement. Missing data is not zero. Invalid monitoring configuration disables metrics with a warning; gameplay still starts. Runtime sampling failures disable sampling with a warning. Shutdown discards queued samples and interrupts delivery without waiting for the monitoring service.

## Local stack

The fixture pins InfluxDB 2.7.12 and Grafana 12.1.1. These are reproducible development versions, not a production security/update policy. It creates a seven-day-retention bucket and provisions a datasource plus seven dashboard panels using Grafana's [InfluxDB configuration](https://grafana.com/docs/grafana/latest/datasources/influxdb/configure/).

1. Copy `ops/observability/.env.example` to `.env` in the same directory and replace all values with independent random secrets. This `.env` is ignored by Git.
2. From the repository root, run `docker compose -f ops/observability/compose.yml up -d`.
3. Open Grafana at `http://127.0.0.1:13000` and sign in as `heist` with `GRAFANA_PASSWORD` from that local file. The dashboard is **GameHeist Server Overview** in the **GameHeist** folder. InfluxDB is at `http://127.0.0.1:18086`.
4. For a private local test, use the file's `INFLUX_TOKEN` as the Minecraft process's `HEIST_INFLUX_TOKEN`, enable metrics, and restart the server. In deployed environments, use separate bucket-scoped write/read tokens for the plugin and Grafana, TLS for remote delivery, and restricted network access. This fixture shares its bootstrap token only for disposable development.
5. Stop the stack with `docker compose -f ops/observability/compose.yml down`. Named volumes preserve configuration and samples. Changing `.env` does not reset credentials in initialized volumes.

No Minecraft server, EULA acceptance, or Kubernetes deployment is included in this stack. Grafana counters reflect synthetic data until a real server exports samples. Per-NPC costs, objective completion timings, storage latency percentiles, alert rules, and load-test baselines remain future work.

## Verification

Unit tests exercise encoding/type validation, authenticated requests, bounded dropping behind a slow receiver, failed responses, recovery, bounded warnings, and shutdown rejection. CI also starts an isolated InfluxDB fixture and verifies a Java exporter sample can be read back.

To reproduce the full local check with the stack running:

```powershell
$env:HEIST_INFLUX_TESTS = 'true'
$taskTokenLine = Get-Content ops/observability/.env | Where-Object { $_.StartsWith('INFLUX_TOKEN=') }
$env:HEIST_INFLUX_TEST_TOKEN = $taskTokenLine.Substring('INFLUX_TOKEN='.Length)
.\gradlew.bat :heist-runtime:test --max-workers=1 --rerun-tasks
python ops/observability/verify.py
Remove-Item Env:HEIST_INFLUX_TESTS
Remove-Item Env:HEIST_INFLUX_TEST_TOKEN
```

The opt-in test uses only localhost port 18086, a test-specific token variable, and a unique synthetic server tag. It does not read the plugin's production URL/token. The verification script checks the provisioned datasource and executes every dashboard query against that sample. Without the opt-in switch, this integration test is skipped.

Verified locally: Java exporter → real InfluxDB write/query, Grafana datasource health, dashboard provisioning, and data returned by all seven panel queries. The dashboard has not been visually inspected in a browser. Real Paper sampling and outage behavior during gameplay still need a dedicated-server/client smoke test.
