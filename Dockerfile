# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-noble AS build

WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/main/ src/main/

RUN --mount=type=cache,target=/root/.m2 \
    sh ./mvnw --batch-mode --no-transfer-progress compile jar:jar spring-boot:repackage

FROM eclipse-temurin:21-jre-noble

WORKDIR /app

RUN groupadd --system ism \
    && useradd --system --gid ism --home-dir /app --no-create-home ism

COPY --from=build --chown=ism:ism /workspace/target/*.jar /app/app.jar

USER ism

EXPOSE 8080

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]