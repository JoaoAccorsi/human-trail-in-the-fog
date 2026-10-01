# Edge simulator (Java 21). The image carries only the dataset rows of the simulated users:
# create data/users.csv first with deploy/extract-users.sh (the full YJMob100K CSV is not copied).
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
COPY data/users.csv ./dataset.csv
ENV HTF_DATASET=/app/dataset.csv
ENTRYPOINT ["java", "-cp", "classes:lib/*", "edge.EdgeApp"]
