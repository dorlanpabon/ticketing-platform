FROM gradle:9.4.1-jdk25@sha256:8e41f079ca18bf00994f0ba7ca5cbfb7b0c2fdafd72bee98e6c3785bd9ac2939 AS build
ARG SERVICE
WORKDIR /workspace
COPY --chown=gradle:gradle gradle ./gradle
COPY --chown=gradle:gradle services/${SERVICE} ./services/${SERVICE}
USER gradle
RUN --mount=type=cache,target=/home/gradle/.gradle,uid=1000,gid=1000 gradle -p services/${SERVICE} :app-service:bootJar --no-daemon --no-configuration-cache -x pitest
RUN cp services/${SERVICE}/applications/app-service/build/libs/*.jar /tmp/application.jar

FROM eclipse-temurin:25-jre-alpine@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918
RUN addgroup -S ticketing && adduser -S -G ticketing -u 10001 ticketing
WORKDIR /app
COPY --from=build --chown=10001:10001 /tmp/application.jar application.jar
USER 10001:10001
VOLUME ["/tmp"]
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=90s --retries=12 CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/application.jar"]
