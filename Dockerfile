FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Dependencies change far less often than sources, so resolve them in their own layer
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies

COPY src src
RUN ./gradlew --no-daemon bootJar \
    && java -Djarmode=tools -jar build/libs/*.jar extract --layers --destination extracted \
    && mv extracted/application/*.jar extracted/application/app.jar

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S spring && adduser -S -G spring spring
WORKDIR /app
# Libraries first, application code last: only the small top layer changes between builds
COPY --from=build --chown=spring:spring /workspace/extracted/dependencies/ ./
COPY --from=build --chown=spring:spring /workspace/extracted/application/ ./
USER spring
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
