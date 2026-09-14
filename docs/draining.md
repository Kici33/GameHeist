# Graceful draining

Draining is a one-way preparation step before ordinary server shutdown. It does not stop the process or abort active heists. Restart to reopen admission.

Run `/heist drain` as an operator. Admission, new profile loads, reloads, and profile edits close together on the owner thread. Existing matches may finish; an operator may explicitly stop an unwanted briefing or match with `/heist stop <uuid>`. Repeat `/heist drain` to inspect progress. An empty briefing is not automatically aborted, so it needs operator action.

Drain completion requires all of the following:

- No active instances, including finalizing results and retrying cleanup.
- No pending profile operations, including loads and saves from disconnected players.
- No unacknowledged profile writes.
- No pending or failed cleanup from partial world creation.

Successful profile saves clear their acknowledgement tracking on the owner tick. Failed or ambiguous saves remain visible even after disconnection. Before draining, a fresh profile read of the exact attempted replacement can resolve an ambiguous acknowledgement; a later acknowledged edit also resolves it. Once draining starts, no reloads or edits are accepted. An unresolved write therefore keeps drain completion false and requires operator investigation. There is no automatic override that declares it saved.

## HTTP contract

The health listener defaults to `127.0.0.1:8081`. `GET /live` remains healthy during a responsive drain. `GET /ready` becomes unavailable. `GET /drained` returns 200 only for a fresh owner-thread snapshot whose drain conditions are satisfied; startup, unfinished work, and stale ticks return 503.

Responses include `activeInstances`, `draining`, `pendingProfileOperations`, `unacknowledgedProfileWrites`, `worldCleanupHealthy`, and `safeToStop`. This is a lifecycle status, not a durability claim for development memory storage.

Remote initiation is disabled by default. Supply `HEIST_DRAIN_TOKEN` in the server environment to enable `POST /drain`. Use a secret containing 32–128 URL-safe letters, digits, underscores, or hyphens. Invalid configured secrets reject plugin initialization. The request must include `Authorization: Bearer <token>`; never put the token in a URL. Keep it in deployment secrets and restrict listener access to the pod or trusted management network.

`POST /drain` returns 202 for an authenticated, idempotent request. It only sets an atomic flag; the owner thread applies the drain on its next tick. Readiness is withdrawn immediately after the request. A 202 response does not mean draining is complete: poll `GET /drained` until it returns 200. Missing/wrong authorization returns 401, unsupported methods return 405, and disabled initiation returns 404.

## Future Kubernetes integration

The implemented `DrainHook` requests drain while the game loop is still running and polls completion with a bounded deadline. The server image and Kubernetes template connect this helper through `preStop`; see [deployment setup](deployment.md). Set the termination budget to cover the intended match-finish policy plus persistence/cleanup. Timeout remains an incomplete drain. The manifests have not been deployed; Agones allocation and live Kubernetes termination verification remain future work.

Direct `/stop`, SIGTERM, plugin disable, and hard process termination do not first execute this protocol. The existing shutdown path remains best-effort and reports unfinished results and profile writes. The drain status does not add a journal, crash recovery, persistent memory-mode storage, or a guarantee against forced termination. Read-only statistics requests are not part of the gameplay/profile acknowledgement barrier.

## Verification

Automated tests exercise authenticated HTTP initiation, wrong tokens, disabled initiation, repeated requests, readiness before the owner tick, stale completion snapshots, disconnected profile operations, ambiguous saves, result acknowledgement, and cleanup retries.

On a 26.1.2 test server, request drain during a heist and during a pending profile save. Verify admissions/edits close, the current crew can finish, and `/drained` remains unavailable until result persistence and world deletion finish. Repeat with MongoDB unavailable and restore it to test result retries. Confirm ordinary shutdown after completion produces no unacknowledged-work warnings. Real Paper/client and Kubernetes tests remain unperformed.
