# Image for the kms-setup tool. Build it first: ./gradlew :kms-setup:installDist (from dcc-tools/)
FROM eclipse-temurin:25-jre
COPY build/install/kms-setup/ /app/
ENTRYPOINT ["/app/bin/kms-setup"]
