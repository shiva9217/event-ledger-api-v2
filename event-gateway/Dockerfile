# ── build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./pom.xml
COPY event-gateway/pom.xml ./event-gateway/pom.xml
RUN mvn -q -pl event-gateway -am dependency:go-offline -B || true
COPY event-gateway/src ./event-gateway/src
RUN mvn -q -pl event-gateway -am clean package -DskipTests -B

# ── runtime stage ───────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/event-gateway/target/event-gateway.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
