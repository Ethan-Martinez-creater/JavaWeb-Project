FROM eclipse-temurin:21.0.9_10-jdk-jammy AS build

WORKDIR /workspace/backend

# Copy the wrapper and dependency descriptor first so source edits do not invalidate dependency download layers.
COPY backend/.mvn .mvn
COPY backend/mvnw backend/pom.xml ./
RUN chmod +x ./mvnw && ./mvnw --batch-mode -DskipTests dependency:go-offline

COPY backend/src ./src
RUN ./mvnw --batch-mode -DskipTests package

FROM eclipse-temurin:21.0.9_10-jre-jammy AS runtime

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/backend/target/eventflow-backend-0.1.0-SNAPSHOT.jar /app/eventflow.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/eventflow.jar"]
