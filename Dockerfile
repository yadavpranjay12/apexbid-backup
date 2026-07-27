# LOCAL DEVELOPMENT BUILD (Uncomment this block for fast local testing)

# FROM eclipse-temurin:21-jre-alpine
# EXPOSE 8080
# RUN mkdir /app
# COPY build/libs/*.jar /app/apexbid.jar
# ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/apexbid.jar"]



# PRODUCTION MULTI-STAGE BUILD (Active for AWS Deployment)

# Build stage
FROM gradle:jdk21 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle build -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
EXPOSE 8080
RUN mkdir /app
COPY --from=build /home/gradle/src/build/libs/*.jar /app/apexbid.jar
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/apexbid.jar"]