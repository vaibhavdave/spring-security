# Build context is the repo root (multi-module Gradle build), not this directory.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :admin-service:bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S spring && adduser -S spring -G spring
COPY --from=build /workspace/admin-service/build/libs/admin-service-0.1.0.jar app.jar
USER spring
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
