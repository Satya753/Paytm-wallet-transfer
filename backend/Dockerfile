# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -q -DskipTests dependency:go-offline

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/wallet-api-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
