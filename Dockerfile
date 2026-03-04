FROM eclipse-temurin:25-jdk

WORKDIR /app

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./

RUN chmod +x ./gradlew

COPY source source
COPY test test
COPY tools tools
COPY cockpit-ui cockpit-ui

EXPOSE 8080

CMD ["sh", "-c", "./gradlew --no-daemon runTestApp --args='--server.port=${PORT:-8080}'"]
