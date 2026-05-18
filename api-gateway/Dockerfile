FROM eclipse-temurin:17-jdk-alpine
RUN apk add --no-cache maven
WORKDIR /app
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java -jar target/api-gateway-*.jar"]
