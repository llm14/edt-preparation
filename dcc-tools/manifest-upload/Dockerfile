# Image for the manifest-upload tool. Build it first: ./gradlew :manifest-upload:installDist (from dcc-tools/)
FROM eclipse-temurin:25-jre
COPY build/install/manifest-upload/ /app/
ENTRYPOINT ["/app/bin/manifest-upload"]
