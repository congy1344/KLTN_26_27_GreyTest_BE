# Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

# Run
FROM eclipse-temurin:17-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 greytest \
    && useradd --uid 10001 --gid greytest --no-create-home --home-dir /app --shell /bin/sh greytest \
    && mkdir -p /app/storage /app/log \
    && chown -R greytest:greytest /app
WORKDIR /app
COPY --from=build --chown=greytest:greytest /app/target/*.jar app.jar
# Docker CLI để backend tạo container sandbox chạy Unit Test (mount socket hoặc qua docker-socket-proxy).
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
EXPOSE 8080
ENTRYPOINT ["docker-entrypoint.sh"]
