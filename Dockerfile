# One runtime image definition for every service. The jar is built first (Cloud Build or ./gradlew bootJar):
#   docker build --build-arg SERVICE=agent-registry -t agent-registry .
FROM eclipse-temurin:21-jre

ARG SERVICE
RUN test -n "$SERVICE" || (echo "Build with --build-arg SERVICE=<service name>" && false) \
    && useradd --system --uid 10001 --no-create-home app

WORKDIR /app
COPY services/${SERVICE}/build/libs/${SERVICE}.jar app.jar

USER 10001
# Size the heap from the container's memory limit; Cloud Run sets PORT (default 8080).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
