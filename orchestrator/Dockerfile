# Image for orchestrator. Build the app first: ./gradlew :orchestrator:build
# The image only copies the result (build/quarkus-app); it does not compile anything.
FROM eclipse-temurin:25-jre

WORKDIR /app

# Quarkus "fast-jar" layout. Dependencies (lib/) change least often, so they are copied
# first: Docker reuses that layer when only our code changes.
COPY build/quarkus-app/lib/     lib/
COPY build/quarkus-app/*.jar    ./
COPY build/quarkus-app/app/     app/
COPY build/quarkus-app/quarkus/ quarkus/

EXPOSE 8082

# Run as a non-root user.
USER 1001

ENTRYPOINT ["java", "-jar", "quarkus-run.jar"]
