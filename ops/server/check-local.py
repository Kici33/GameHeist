"""Read-only local readiness check. Does not start Paper, accept EULA, or request drain."""
import argparse
import json
from pathlib import Path
import sys
from datetime import datetime, timezone
from urllib.request import urlopen
from urllib.error import HTTPError, URLError


def validate(status, body):
    if status != 200:
        return f"HTTP {status}: endpoint is not healthy"
    if not isinstance(body, dict):
        return "Response must be a JSON object"
    for field in ("healthy", "draining", "worldCleanupHealthy", "safeToStop"):
        if type(body.get(field)) is not bool:
            return f"Missing or invalid {field}"
    for field in ("activeInstances", "pendingProfileOperations", "unacknowledgedProfileWrites"):
        if type(body.get(field)) is not int or body[field] < 0:
            return f"Missing or invalid {field}"
    if not body["healthy"] or not body["worldCleanupHealthy"]:
        return "Heartbeat or world cleanup is unhealthy"
    if body["draining"]:
        return "Server is draining; new practice sessions are disabled"
    if body["unacknowledgedProfileWrites"]:
        return "Unacknowledged profile writes require investigation"
    return None


def probe(port, endpoint):
    try:
        try:
            response = urlopen(f"http://127.0.0.1:{port}/{endpoint}", timeout=3)
        except HTTPError as failure:
            response = failure
        with response:
            status = response.code
            raw = response.read(65537)
            if len(raw) > 65536:
                raise ValueError("Oversized health response")
            body = json.loads(raw)
        error = validate(status, body)
        return {"endpoint": endpoint, "passed": error is None, "status": status, "body": body, "error": error}
    except (URLError, OSError, ValueError) as failure:
        return {"endpoint": endpoint, "passed": False, "error": str(failure)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8081)
    parser.add_argument("--report", type=Path, default=Path("build/local-readiness.json"))
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("Port must be between 1 and 65535")
    checks = [probe(args.port, endpoint) for endpoint in ("live", "ready")]
    passed = all(check["passed"] for check in checks)
    report = {"checkedAt": datetime.now(timezone.utc).isoformat(), "passed": passed, "checks": checks,
              "scope": "HTTP readiness only; client gameplay, pack rendering, and MongoDB acceptance remain unverified"}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for check in checks:
        print(f"{'PASS' if check['passed'] else 'FAIL'} /{check['endpoint']}: {check['error'] or 'healthy'}")
    print(f"Report: {args.report.resolve()}")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
