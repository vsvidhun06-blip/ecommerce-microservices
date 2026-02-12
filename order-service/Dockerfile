FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY .mvn/ .mvn
COPY mvnw pom.xml ./
RUN ./mvnw dependency:go-offline
COPY src ./src
RUN ./mvnw clean package -DskipTests
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "target/order-service-0.0.1-SNAPSHOT.jar"]
