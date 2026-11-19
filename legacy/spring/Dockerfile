FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradle gradle
COPY gradlew build.gradle settings.gradle gradle.properties ./
RUN chmod +x gradlew
COPY src src
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
RUN groupadd --gid 10001 villa && useradd --uid 10001 --gid villa --no-create-home villa
WORKDIR /app
COPY --from=build /workspace/build/libs/*-SNAPSHOT.jar app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=UTC"
EXPOSE 61228
ENTRYPOINT ["java","-jar","app.jar"]
