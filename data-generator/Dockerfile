# syntax=docker/dockerfile:1.6

FROM docker.io/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM docker.io/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/data-generator-*.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
