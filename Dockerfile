FROM eclipse-temurin:25-jdk AS build

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

RUN chmod +x ./gradlew

COPY source source
COPY test test
COPY tools tools
COPY cockpit-ui cockpit-ui

RUN ./gradlew --no-daemon testAppBundle

FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build /workspace/build/libs/*-test-app.jar /app/app.jar
COPY --from=build /workspace/build/test-app-libs /app/lib

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT:-8080} -cp /app/app.jar:/app/lib/* io.flowlite.test.TestApplicationMainKt"]
