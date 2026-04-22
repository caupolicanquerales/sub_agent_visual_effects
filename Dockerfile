# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /app/target/sub_agent_visual_effects-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8093
ENTRYPOINT ["java", "-jar", "app.jar"]
