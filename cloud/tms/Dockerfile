# Trail Management Service (Java 17). The JSONL files live in /app/data (mount a volume there).
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/classes ./classes
COPY --from=build /src/target/lib ./lib
RUN mkdir -p data
VOLUME /app/data
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "classes:lib/*", "tms.TmsApp"]
