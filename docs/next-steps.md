# Next implementation slices

1. **Graybox smoke test:** The physical objective slice is implemented in `graybox:2`: security, timed/jamming drill, proximity repairs, loot, extraction voting, and result feedback. Run the 26.1.2 client smoke test before claiming playtest acceptance. This is still a sparse prop layout, not the finished bank map.
2. **Guarded smoke test:** `graybox:3` implements native guard patrols, perception, suspicion, noise investigation, alarm attempts, last-seen pursuit/search, and scoped cleanup. Verify native movement and cover on a 26.1.2 server. Combat, weapons, reinforcements, and final NPC art remain later gameplay work.
3. **Player slice:** Profile loading, preset/settings commands, MongoDB revision writes, and durable match results are implemented. Run the client/restart smoke test. Scoped personal statistics are also implemented from saved results. Next add the unique transactional receipt worker; keep rewards disabled until replay and concurrency tests pass.
4. **Networking slice:** Implement BungeeCord/lobby and coordinator admission as a complete transfer path, including stale grants and failed-transfer recovery.
5. **Deployment slice:** Pinned server image, Kubernetes lab manifests, probes, resource limits, and preStop drain helper are implemented. Packaging and manifest validation passed without starting Minecraft. Next verify a reviewed dedicated server/pod, prepare immutable world templates, connect Agones allocation, and exercise two independent pods.
6. **Observability slice:** Bounded InfluxDB export and a provisioned Grafana overview are implemented and locally verified with synthetic samples. Add per-NPC/objective/storage timing, alerts, and real gameplay/load-test baselines.
7. **Presentation slice:** Replace placeholder graybox content with original models, textures, sound, and a required versioned pack.

For every slice: add behavior tests, run the dedicated-server smoke test, and update the README's verified capabilities. The broader design and acceptance targets remain in the GDD.

The player storage foundation is implemented. Scoped statistics are implemented. The next foundation milestone is receipt processing, followed by the lobby transfer path. Wider statistics need gameplay contribution tracking first. Kubernetes and network integration remain independent later steps, as planned in the GDD. The guarded map also needs playtest feedback before adding combat pressure.
