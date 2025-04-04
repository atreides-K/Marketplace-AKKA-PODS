# Use a Maven + OpenJDK 17 base image
FROM maven:3.9.6-eclipse-temurin-21 AS build

# Set working directory
WORKDIR /app

# Copy the Maven project files
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy the source code
COPY src ./src

# Build the JAR
RUN mvn clean package -DskipTests

# Second stage: use a lightweight Java 17 runtime
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=build /app/target/user-service-0.0.1-SNAPSHOT.jar user-service.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "user-service.jar"]
