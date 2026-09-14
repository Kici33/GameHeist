# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:25-jre-jammy@sha256:20a695e74d47fb29cda1cbad5d9ee6cfad4ac6e88a8e048ed6265cede1e71f5e

# Paper 26.1.2 build 74, verified against PaperMC's v3 downloads service.
ADD --checksum=sha256:1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7 https://fill-data.papermc.io/v1/objects/1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7/paper-26.1.2-74.jar /opt/gameheist/paper.jar
COPY heist-paper/build/libs/heist-paper-0.1.0-SNAPSHOT.jar /opt/gameheist/gameheist.jar
COPY ops/server/start.sh /opt/gameheist/start.sh
RUN groupadd --gid 10001 heist && useradd --uid 10001 --gid 10001 --no-create-home heist \
    && mkdir /data && chown 10001:10001 /data \
    && chmod 0444 /opt/gameheist/paper.jar /opt/gameheist/gameheist.jar \
    && chmod 0555 /opt/gameheist/start.sh
USER 10001:10001
WORKDIR /data
ENV EULA=false
EXPOSE 25565 8081
STOPSIGNAL SIGTERM
ENTRYPOINT ["/opt/gameheist/start.sh"]
