# syntax=docker/dockerfile:1

FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache su-exec \
    && mkdir -p /app /data \
    && chown 1000:1000 /data
COPY --from=build /build/target/heim.jar /app/heim.jar
COPY --chmod=755 docker-entrypoint.sh /app/docker-entrypoint.sh

# The entrypoint starts as root only to fix the data directory's owner, then runs Heim as PUID:PGID.
ENV HEIM_DATA_DIR=/data PUID=1000 PGID=1000
EXPOSE 8000
VOLUME /data

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O /dev/null http://localhost:8000/api/health || exit 1

ENTRYPOINT ["/app/docker-entrypoint.sh"]
