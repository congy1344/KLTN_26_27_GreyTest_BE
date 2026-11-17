# Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q clean package -DskipTests

# Run
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
# Docker CLI để backend tạo container sandbox chạy Unit Test (cần mount /var/run/docker.sock).
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
