# Image for the DSP simulator. Build it first: ./gradlew :dsp-simulator:installDist (from dcc-tools/)
FROM eclipse-temurin:25-jre
COPY build/install/dsp-simulator/ /app/
EXPOSE 8090
USER 1001
ENTRYPOINT ["/app/bin/dsp-simulator"]
