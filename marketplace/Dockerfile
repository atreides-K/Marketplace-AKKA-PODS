# Build stage: download dependencies and build the project
# Using a specific Java 17 image consistent with pom.xml target
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
# Copy the Maven project file and download dependencies offline
COPY pom.xml .
# REMOVED --quiet to make output visible
RUN mvn dependency:go-offline 
# Copy the source code
COPY src ./src
# Build the project (skipping tests)
# Ensure target directory is created
# This command should already show INFO lines
RUN mvn clean package -DskipTests

# Final stage: run the application using mvn exec:java
# Using the same base image because Maven is needed to run the app
FROM maven:3.9.6-eclipse-temurin-17
WORKDIR /app
# Copy the entire built project from the build stage
# This includes pom.xml, src, and target/, necessary for mvn exec:java
COPY --from=build /app /app

# PHASE 2 CHANGE: Removed EXPOSE as it's irrelevant with --net=host
# EXPOSE 8080

# PHASE 2 CHANGE: Correct ENTRYPOINT to use mvn exec:java as required
# The -Dexec.args=<port> will be appended by the docker run command
ENTRYPOINT ["mvn", "exec:java", "-Dexec.mainClass=pods.akka.Main"]