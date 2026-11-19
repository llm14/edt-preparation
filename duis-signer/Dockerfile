# Image for duis-signer. Build the app first: ./gradlew :duis-signer:build
# The image only copies the result (build/quarkus-app); it does not compile anything.
FROM eclipse-temurin:25-jre

WORKDIR /app

# Quarkus "fast-jar" layout. Dependencies (lib/) change least often, so they are copied
# first: Docker reuses that layer when only our code changes.
COPY build/quarkus-app/lib/     lib/
COPY build/quarkus-app/*.jar    ./
COPY build/quarkus-app/app/     app/
COPY build/quarkus-app/quarkus/ quarkus/

EXPOSE 8083

# Run as a non-root user.
USER 1001

# --enable-native-access: Java 25 prints a warning (not JSON) when the gRPC Netty library loads its native code; the log
# agent reads every line as JSON, so the flag is set. See openspec/specs/structured-logging.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "quarkus-run.jar"]
