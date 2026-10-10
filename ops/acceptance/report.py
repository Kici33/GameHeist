"""Fail acceptance if any expected live suite is missing, skipped, or unsuccessful."""
from pathlib import Path
import os
import sys
import xml.etree.ElementTree as ET

SUITES = {
    "heist-mongo": ["MongoStoreIntegrationTest", "MongoFailureIntegrationTest",
                    "MongoRewardsIntegrationTest", "MongoReservationsIntegrationTest", "MongoNetworkIntegrationTest"],
    "heist-runtime": ["InfluxIntegrationTest", "ProfileSessionsTest", "InstanceManagerTest", "MetricsTest"],
}


def report(root):
    lines = ["# Database and telemetry acceptance", "",
             "| Suite | Tests | Failures | Errors | Skipped |", "|---|---:|---:|---:|---:|"]
    problems = []
    for module, suites in SUITES.items():
        for suite in suites:
            files = list((root / module / "build/test-results/test").glob(f"TEST-*.{suite}.xml"))
            if len(files) != 1:
                problems.append(f"Missing or ambiguous report: {suite}")
                lines.append(f"| {suite} | missing | | | |")
                continue
            result = ET.parse(files[0]).getroot()
            counts = [int(result.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")]
            lines.append(f"| {suite} | " + " | ".join(map(str, counts)) + " |")
            if counts[0] == 0 or any(counts[1:]):
                problems.append(f"Suite did not fully pass: {suite}")
    lines.extend(["", "FAIL" if problems else "PASS", *problems, "",
                  "Fixtures: isolated MongoDB 8.0.12 (standalone and replica set), InfluxDB 2.7.12.",
                  "Hard crash: unacknowledged profile/result writes can be lost before durable commit; "
                  "committed immutable results and reward jobs survive client restart. Telemetry is best effort."])
    return "\n".join(lines) + "\n", bool(problems)


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    content, failed = report(root)
    output = root / "build/acceptance/summary.md"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(content, encoding="utf-8")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(summary, "a", encoding="utf-8") as stream:
            stream.write(content)
    print(content)
    sys.exit(1 if failed else 0)
