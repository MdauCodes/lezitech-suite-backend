FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
RUN ./mvnw -q -B -DskipTests package && cp target/lezitech-suite-api-*.jar /app.jar

FROM eclipse-temurin:21-jre
ENV EMBEDDED_DB=false
COPY --from=build /app.jar /app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
