# ── build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Copy parent POM + module POM first for dependency caching
COPY pom.xml ./pom.xml
COPY account-service/pom.xml ./account-service/pom.xml
COPY event-gateway/pom.xml ./event-gateway/pom.xml
RUN mvn -q -pl account-service dependency:go-offline -B || true
COPY account-service/src ./account-service/src
RUN mvn -q -pl account-service -am clean package -DskipTests -B

# ── runtime stage ───────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/account-service/target/account-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
