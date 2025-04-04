# Build stage: download dependencies and build the project
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
# Copy the Maven project file and download dependencies offline
COPY pom.xml .
RUN mvn dependency:go-offline
# Copy the source code
COPY src ./src
# Build the project (skipping tests)
RUN mvn clean install -DskipTests

# Final stage: run the application using mvn exec:java
FROM maven:3.9.6-eclipse-temurin-21
WORKDIR /app
# Copy the entire built project from the build stage
COPY --from=build /app /app
# Expose the port (adjust if needed)
EXPOSE 8080
# Run the application using mvn exec:java
CMD ["mvn", "exec:java"]
