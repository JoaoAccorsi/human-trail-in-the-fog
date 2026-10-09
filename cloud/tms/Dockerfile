# Trail Management Service (Java 21). The data lives in PostgreSQL + PostGIS: set HTF_TMS_DB_URL,
# HTF_TMS_DB_USER and HTF_TMS_DB_PASSWORD (docker-compose.yml; on AWS, the RDS instance).
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
EXPOSE 8080
ENTRYPOINT ["java", "-cp", "classes:lib/*", "tms.TmsApp"]
