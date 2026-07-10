FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY build.gradle settings.gradle ./
COPY src src

RUN chmod +x ./gradlew
RUN ./gradlew clean bootJar --no-daemon
RUN JAR_FILE="$(find build/libs -name '*.jar' ! -name '*plain.jar' | head -n 1)" \
    && cp "$JAR_FILE" app.jar

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser

COPY --from=builder /workspace/app.jar /app/app.jar

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
