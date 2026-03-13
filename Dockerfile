# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

# Copia o pom primeiro para cachear dependências
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copia o restante do código e compila
COPY src ./src
RUN mvn clean package -DskipTests -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Copia apenas o JAR final
COPY --from=build /app/target/brasfoot-transfer-1.0.0.jar app.jar

# Render injeta a variável PORT dinamicamente; o Spring usa SERVER_PORT
ENV SERVER_PORT=8081

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]
