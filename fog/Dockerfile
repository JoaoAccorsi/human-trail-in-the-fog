# Fog node (Java 21 + embedded Moquette broker). One container = one Fog node (HTF_FOG_IDS).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/classes ./classes
COPY --from=build /src/target/lib ./lib
# Fog N: MQTT broker on 1900+N and HTTP API (Chord + handover) on 9000+N (ports 1901-1916 and
# 9001-9016 with the default 16 nodes; on AWS the containers use the host network, so EXPOSE is only informative).
# The store-and-forward queue is written to /app/store-and-forward-queue: mount a volume there.
EXPOSE 1901-1916 9001-9016
ENTRYPOINT ["java", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn", "-cp", "classes:lib/*", "fog.FogApp"]
