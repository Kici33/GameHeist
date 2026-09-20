# Server image and Kubernetes lab

This milestone packages the practice server and its drain protocol. An opt-in two-replica Agones Fleet and SDK lifecycle are now included; see [Agones setup, release and acceptance](agones.md). Real proxy registration, real-cluster failure evidence and asynchronous production-map template preparation remain open.

## Image build

From the repository root:

```powershell
.\gradlew.bat build --max-workers=1
docker build -f ops/server/Dockerfile -t gameheist:26.1.2-dev .
python ops/server/verify.py
```

The image copies the locally built plugin jar, uses a digest-pinned Eclipse Temurin Java 25 JRE, and downloads **Paper 26.1.2 build 74** with checksum verification. The SHA-256 was verified through [PaperMC's downloads service](https://docs.papermc.io/misc/downloads-service/): `1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7`. There is no floating Paper/latest download at runtime. Paper may still obtain its upstream server/libraries during its first actual startup; offline-first boot is not established.

The Docker context is restricted to the plugin jar and startup files. Credentials, Git history, local monitoring `.env`, source caches, and generated server worlds are excluded. CI builds this image and verifies refusal paths without starting Minecraft. The locally built plugin must correspond to the source being deployed; run the build first rather than packaging an older jar.

The image uses UID/GID 10001 and runs Java as PID 1. `/data` is the ephemeral writable server workspace; `/tmp` must also be writable. Reviewed configuration is mounted read-only at `/config`. The entrypoint requires `HEIST_MONGODB_URI`, `HEIST_DRAIN_TOKEN`, and explicit `EULA=true`. It copies only the reviewed runtime configuration and plugin into the workspace. It does not accept the EULA by default.

## Review the deployment

Render without contacting a cluster:

```powershell
kubectl kustomize ops/kubernetes -o build/deployment/gameheist.yaml
```

The Kustomize template includes a namespace, immutable generated configuration, restricted identities, a two-replica Agones Fleet, and an ingress NetworkPolicy. It remains intentionally undeployable with `EULA=false`, placeholder digests and placeholder pack coordinates. It disables backend service-account token mounting, runs non-root with a read-only root filesystem and no Linux capabilities, and gives each pod its own bounded `emptyDir` workspace.

Before creating a running pod:

1. Review Minecraft's EULA yourself and explicitly change `EULA` to `true` only if you accept it. Use a private overlay; do not edit and publish credentials in the base.
2. Push the built image to your registry or import it into your local cluster. Replace `gameheist:26.1.2-dev` with the immutable deployed image digest. The template tag is only a local build convenience; it is not a published image.
3. Supply a `gameheist-secrets` Secret in the `gameheist` namespace with `mongodb-uri`, `drain-token`, and `proxy-forwarding-secret` keys. Drain and forwarding tokens require 32–128 URL-safe characters. The forwarding secret must match the private Velocity proxy. Add `influx-token` only if enabling metrics. Use your secret-management process; no actual credentials are included here.
4. Review `ops/kubernetes/config/config.yml`. MongoDB mode is selected. Configure a real required resource-pack HTTPS URL and SHA-1. An explicit development bypass is possible for private graybox testing, but it is off in this template. Empty pack settings intentionally prevent successful plugin startup.
5. Populate the reviewed `whitelist.json` and `ops.json` with your actual test accounts as appropriate. Both are empty by default, whitelist enforcement is on, online authentication is enabled, and RCON/query are off. Console changes to these files will not survive a pod replacement; update the configuration source.
6. If enabling metrics, configure a reachable InfluxDB origin and bucket. `HEIST_SERVER_ID` overrides the static metrics server name with the pod name. Do not expose the health listener publicly; authenticated drain remains management-only even within the cluster.
7. Review CPU, memory, disk, and timeout sizing using real load tests. The template requests one CPU/2 GiB, limits two CPUs/3 GiB, and allows 4 GiB ephemeral storage. Java uses at most 65% of the memory limit for heap, leaving room for native allocations. These are initial lab budgets, not proven capacity figures.

After that review, applying the manifests is an operator action. No cluster resources were applied by the implementation run documented here. The Agones lifecycle gates Mongo crew claims on observed allocation, but the proxy must still register the returned private pod endpoint under the exact GameServer name. `/ready` is a health/admission signal, not a complete matchmaking protocol.

## Shutdown contract

Kubernetes `preStop` runs `DrainHook` from the plugin jar with a 32 MiB helper heap. It posts the authenticated request to loopback port 8081 and polls `/drained`. It never invokes Paper or accesses Bukkit. Authentication/configuration failures return exit code 2; budget expiry/interruption returns 1; an acknowledged drain returns 0. Redirects are rejected and secrets are not logged or passed as command arguments.

The template gives the hook 1250 seconds within a 1300-second termination grace period, leaving 50 seconds for ordinary Paper shutdown. Current arena deadlines are 1200 seconds, but pending persistence/cleanup, empty briefings, and unacknowledged profiles can keep drain incomplete. The budget is a limit, not a success guarantee. See the [drain protocol](draining.md).

Kubernetes starts the grace countdown **before** running `preStop`, and ultimately terminates the container regardless of the hook's outcome. A failed hook does not veto termination. This behavior is documented in [container lifecycle hooks](https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/). A stalled process, unavailable database, forced deletion, node loss, or expired budget may still lose unacknowledged data. Production allocation/drain must happen ahead of termination and needs recovery design.

## Verified and remaining

Verified locally: plugin build; image build; pinned Paper checksum inside the image; non-root image configuration; shell syntax; default EULA refusal; standalone hook packaging; hook authentication, polling, and deadlines against local HTTP fixtures; four rendered Kubernetes resources passing strict kubeconform validation.

Not verified: actual Minecraft startup, resource downloads on first boot, client interaction in the image, whitelist/pack behavior in a pod, kubelet probes, termination hooks on a live cluster, or two independent game pods. No EULA acceptance was written and no Minecraft process was started. A dedicated-server smoke test is the next acceptance step once its configuration and EULA decision are supplied.
