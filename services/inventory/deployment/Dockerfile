FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918
# NOTE: If the Java base image already provides the user and its permissions, remove this line
RUN addgroup -S -g 10001 appgroup && adduser -S -u 10001 appuser -G appgroup
WORKDIR /app
COPY *.jar inventory.jar
# NOTE: If the Java base image already provides the user and its permissions, remove this line
RUN chown -R appuser:appgroup /app
ENV JAVA_OPTS="-XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
# Replace with a non-root user to avoid running the container with excessive privileges
RUN chmod 1777 /tmp
VOLUME ["/tmp"]
USER 10001:10001
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=12 CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT [ "/bin/sh", "-c", "exec /opt/java/openjdk/bin/java $JAVA_OPTS  -jar /app/inventory.jar" ]
