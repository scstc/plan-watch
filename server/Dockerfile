# Build stage - Use JDK 26 to build the application
FROM eclipse-temurin:26-jdk-jammy AS builder

WORKDIR /build

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Copy pom.xml first for better caching
COPY pom.xml .

# Download dependencies
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the application
RUN mvn clean package -DskipTests -B

# Runtime stage - Use smaller JDK 26 runtime image
FROM eclipse-temurin:26-jre-jammy

WORKDIR /app

# Create data directory for config.json
RUN mkdir -p /app/data

# Copy the JAR from builder
COPY --from=builder /build/target/*.jar app.jar

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
