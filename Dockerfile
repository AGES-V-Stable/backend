FROM gradle:9-jdk25 AS build
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src
RUN gradle build --no-daemon



FROM eclipse-temurin:25-jre
RUN mkdir /app
COPY --from=build /home/gradle/src/build/libs/*.jar /app/backend.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UnlockExperimentalVMOptions", "-jar", "/app/backend.jar"]
