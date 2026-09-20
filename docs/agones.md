# Agones match lifecycle and two-pod acceptance

GameHeist includes an opt-in Agones 1.60 Fleet for two warm `brasslock_bank:1` Normal practice backends. The integration is deliberately fail closed: a backend can prepare its empty world and register its Mongo lease, but it cannot claim a queued crew until the Agones SDK reports that exact GameServer as `Allocated`.

## Lifecycle contract

When Agones injects `AGONES_SDK_HTTP_PORT`, the plugin uses the SDK's loopback REST endpoint. It sends health every two seconds, requests `Ready` only after the resource pack, Mongo network lease and prepared backend are ready, and polls `/gameserver` for the observed state. Mongo allocation remains gated until `Allocated`. SDK requests never run on Paper's owner thread.

One Agones GameServer hosts one match. After the bound match result is acknowledged, its world is removed and reservation release completes, the backend drains. Only when the existing drain barrier reports no instance, pending profile operation, unacknowledged profile write or unhealthy world cleanup does it request Agones `Shutdown`. Kubernetes `preStop` retains the authenticated drain fallback for external termination. A hard node loss cannot transparently restore a live match; Mongo lease expiry and lobby reconciliation release the reservation for a visible retry.

The Fleet uses two replicas, distributed scheduling, `maxUnavailable: 0`, no host port and no Service. Minecraft ingress is selected only from proxy pods in a namespace labeled `gameheist.dev/proxy=true`. Health is not published through a Service. The backend service account has no token. A separate proxy allocator identity receives only namespaced `create/get` on GameServerAllocations and `get/list/watch` on GameServers; it cannot mutate pods, secrets, Fleets or other workloads.

Kubernetes NetworkPolicy requires a supporting CNI. Traffic from a node to its pods is exempt in Kubernetes' policy model, so node firewall rules and private nodes remain mandatory. No public LoadBalancer, NodePort or Agones host port is created by these manifests.

## Immutable release preparation

The checked-in manifest is intentionally undeployable. Before a release, create a private overlay which replaces all of these values:

- the image with an immutable registry digest;
- `replace-with-git-sha` in the Fleet labels and versioned ConfigMap name;
- `replace-with-image-digest` in the image, world-template identity and annotations;
- the resource-pack HTTPS URL and SHA-1 in `config.yml`, plus the matching Fleet annotation;
- `EULA=false`, but only after reviewing and accepting Mojang's EULA;
- the empty whitelist and operator files with reviewed test accounts;
- secrets named `gameheist-secrets` containing `mongodb-uri`, `drain-token`, `proxy-forwarding-secret`, and optional `influx-token`. The forwarding value is a 32–128 character URL-safe secret shared with Velocity and is written only into the pod's ephemeral Paper configuration.

Arena definitions and the generated graybox scene are packaged in the digest-pinned plugin image. The pod prepares one empty world before becoming Ready. This gives deterministic, immutable content binding, but the current small graybox is still generated on Paper's owner thread. A copied production-scale world template and asynchronous disk preparation remain unfinished; do not describe this implementation as proving cold-start performance for an authored bank.

The Velocity proxy must authenticate users, use modern forwarding with the shared secret, keep `bungee-plugin-message-channel=true`, and dynamically register each allocated GameServer's pod address under the exact GameServer/pod name supplied as `HEIST_SERVER_ID`. Paper uses `online-mode=false`, Velocity forwarding, and disables legacy Bungee identity forwarding. The namespaced RBAC watch is provided for that registrar, but a proxy registrar implementation is not part of this repository yet. Therefore real exact-backend transfer acceptance remains open even though the Paper/Agones allocation fence is implemented.

## Render, validate and deploy

Agones 1.60 supports Kubernetes 1.34–1.36. Install it with Helm using unique generated certificates and restrict its GameServer namespaces to `gameheist`. Label the private proxy namespace and service account to match the manifests. See the official [Agones installation matrix](https://agones.dev/site/docs/installation/) and [Helm installation](https://agones.dev/site/docs/installation/install-agones/helm/).

Render locally without a cluster:

```powershell
kubectl kustomize ops/kubernetes -o build/deployment/gameheist-agones.yaml
```

After applying the private release overlay, validate against the real API server without changing it:

```powershell
./ops/kubernetes/verify-agones.ps1
```

Deploy and allocate both warm GameServers:

```powershell
./ops/kubernetes/verify-agones.ps1 -Apply
```

The script refuses placeholders and unaccepted EULA configuration, performs server-side dry-run validation, waits for exactly two Ready Fleet members, submits two independent allocations, checks distinct GameServer identities, and verifies the pod's restricted service account and readiness probe. `-RunFailureTests` deletes one allocated pod and is intentionally destructive; use it only in a disposable acceptance namespace after arranging lobby observation.

The example `allocation.yaml` binds Fleet, release, arena version and difficulty selectors and adds a reservation annotation. A production coordinator should create that object first, wait for `Allocated`, register the returned address with the proxy, and only then allow the allocated backend to claim the matching durable queue. The current backend claims the oldest compatible Mongo queue after observing allocation; correlating a specific reservation annotation through the proxy registrar remains required for production.

## Acceptance record

Locally verified on 2026-09-20:

- the Gradle suite, including mocked Agones health, Ready, Allocated and Shutdown transitions;
- owner-thread nonblocking behavior and drain-barrier shutdown gating;
- Kubernetes 1.34.11 Kustomize rendering of Namespace, immutable ConfigMap, restricted ServiceAccount/RBAC, two-replica Fleet and NetworkPolicy;
- PowerShell acceptance-script parsing and the container startup script changes;
- no EULA acceptance, cluster mutation or Minecraft process was performed.

The following evidence is still required before closing issue #11:

- a real Agones 1.60 cluster run with two Ready and two distinct Allocated GameServers;
- proxy registrar and exact-name transfer with authenticated UUID forwarding;
- real-client validation that the two crews stay isolated and each reaches its assigned pod;
- DB outage, proxy loss, backend loss and node loss with visible lobby recovery;
- probe and graceful termination events, result/profile acknowledgement, and forced-deletion loss recording;
- CNI policy tests from allowed proxy, unrelated pod and outside the private network;
- warm allocation-to-ready timings and an asynchronous immutable template implementation for the authored production map.

## Rollout, rollback and retention

Publish the plugin image and resource-pack archive before changing the Fleet. Keep both immutable artifacts available while any Ready, Reserved or Allocated GameServer references their release labels. Rolling update uses one surge and zero unavailable Ready servers; Agones leaves Allocated old-release matches running until they shut down. Never delete an old pack just because the Fleet template changed.

Rollback by reapplying the previous reviewed overlay and digest. Agones replaces nonallocated servers with the prior template while preserving allocated sessions. Confirm two Ready previous-release servers before removing the failed release. Mongo documents retain arena version, backend incarnation and match identity; rollback must not reuse a GameServer name or mutable image/pack URL. Forced deletion or node loss aborts the current match and routes players back through durable lobby recovery rather than restoring in-memory gameplay.
