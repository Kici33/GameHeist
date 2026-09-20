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
mkdir -p /data/config
for file in whitelist.json ops.json spigot.yml; do
    if [ -r "/config/$file" ]; then cp "/config/$file" "/data/$file"; fi
done
if [ -r /config/paper-global.yml ]; then
    if grep -q '__HEIST_PROXY_FORWARDING_SECRET__' /config/paper-global.yml; then
        proxy_secret=${HEIST_PROXY_FORWARDING_SECRET:-}
        case "$proxy_secret" in *[!A-Za-z0-9_-]*) echo 'HEIST_PROXY_FORWARDING_SECRET must be 32-128 URL-safe characters.' >&2; exit 78;; esac
        if [ "${#proxy_secret}" -lt 32 ] || [ "${#proxy_secret}" -gt 128 ]; then
            echo 'HEIST_PROXY_FORWARDING_SECRET must be 32-128 URL-safe characters.' >&2
            exit 78
        fi
        while IFS= read -r line || [ -n "$line" ]; do
            if [ "$line" = "    secret: '__HEIST_PROXY_FORWARDING_SECRET__'" ]; then
                printf "    secret: '%s'\n" "$proxy_secret"
            else
                printf '%s\n' "$line"
            fi
        done < /config/paper-global.yml > /data/config/paper-global.yml
        unset proxy_secret
    else
        cp /config/paper-global.yml /data/config/paper-global.yml
    fi
fi
printf 'eula=true\n' > /data/eula.txt
# exec makes Java PID 1 so SIGTERM reaches Paper after the Kubernetes preStop hook.
exec java -XX:InitialRAMPercentage=25 -XX:MaxRAMPercentage=65 -jar /opt/gameheist/paper.jar --nogui
