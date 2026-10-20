# syntax=docker/dockerfile:1

FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -g 1000 heim \
    && adduser -u 1000 -G heim -D -H heim \
    && mkdir -p /app /data \
    && chown heim:heim /data
COPY --from=build /build/target/heim.jar /app/heim.jar

ENV HEIM_DATA_DIR=/data
USER 1000:1000
EXPOSE 8000
VOLUME /data

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget -q -O /dev/null http://localhost:8000/api/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/heim.jar"]
