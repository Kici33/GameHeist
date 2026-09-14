#!/bin/sh
set -eu

if [ "${EULA:-false}" != "true" ]; then
    echo 'Minecraft EULA acceptance is required. Review https://aka.ms/MinecraftEULA and explicitly set EULA=true to run.' >&2
    exit 78
fi
if [ ! -r /config/config.yml ] || [ ! -r /config/server.properties ]; then
    echo 'Mount reviewed config.yml and server.properties in /config before starting.' >&2
    exit 78
fi
if [ -z "${HEIST_MONGODB_URI:-}" ] || [ -z "${HEIST_DRAIN_TOKEN:-}" ]; then
    echo 'HEIST_MONGODB_URI and HEIST_DRAIN_TOKEN must be supplied through runtime secrets.' >&2
    exit 78
fi

# Only the ephemeral server workspace is writable. Authoritative state lives in MongoDB.
umask 077
mkdir -p /data/plugins/GameHeist
cp /opt/gameheist/gameheist.jar /data/plugins/GameHeist.jar
cp /config/config.yml /data/plugins/GameHeist/config.yml
cp /config/server.properties /data/server.properties
for file in whitelist.json ops.json; do
    if [ -r "/config/$file" ]; then cp "/config/$file" "/data/$file"; fi
done
printf 'eula=true\n' > /data/eula.txt
# exec makes Java PID 1 so SIGTERM reaches Paper after the Kubernetes preStop hook.
exec java -XX:InitialRAMPercentage=25 -XX:MaxRAMPercentage=65 -jar /opt/gameheist/paper.jar --nogui
