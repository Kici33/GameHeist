"""Static checks for Kustomize output. Real Agones schema validation stays server-side."""
from pathlib import Path
import sys

path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("build/deployment/gameheist-agones.yaml")
text = path.read_text(encoding="utf-8")
documents = [item for item in text.split("\n---\n") if item.strip()]
kinds = []
for document in documents:
    for line in document.splitlines():
        if line.startswith("kind: "):
            kinds.append(line.removeprefix("kind: ").strip())
            break

assert kinds.count("Fleet") == 1, kinds
assert kinds.count("NetworkPolicy") == 1, kinds
assert kinds.count("ServiceAccount") == 1, kinds
assert kinds.count("Role") == 1 and kinds.count("RoleBinding") == 1, kinds
assert "Deployment" not in kinds and "Service" not in kinds, kinds
for required in (
    "replicas: 2",
    "maxUnavailable: 0",
    "portPolicy: None",
    "automountServiceAccountToken: false",
    "immutable: true",
    "gameheist.dev/world-template:",
    "gameheist.dev/resource-pack-sha1:",
    "name: HEIST_PROXY_FORWARDING_SECRET",
    "__HEIST_PROXY_FORWARDING_SECRET__",
    "path: /ready",
    "name: gameheist-proxy-allocator",
):
    assert required in text, f"missing rendered contract: {required}"
assert text.count("containerPort: 25565") == 2
print(f"Verified {len(documents)} rendered resources and the two-pod Agones security/lifecycle contract.")
