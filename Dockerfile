# Multi-stage build for Render (free tier).
#
# Stage 1 builds the Vite frontend into api/src/main/resources/static
# (same layout as `npm run build:spring`). Stage 2 packages the Spring Boot
# jar WITHOUT the `frontend` Maven profile, since the static bundle is
# already in place — that profile would require npm on the Maven image.

# --- Stage 1: frontend ---
FROM node:22-bookworm-slim AS frontend
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN cd frontend && npm ci --no-audit --no-fund
COPY frontend ./frontend
RUN cd frontend && npm run build:spring

# --- Stage 2: backend ---
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /app
# Copy pom files first for better layer caching of dependencies.
COPY pom.xml .
COPY api/pom.xml api/
COPY dto/pom.xml dto/
COPY repository/pom.xml repository/
COPY service/pom.xml service/
COPY security/pom.xml security/
COPY common/pom.xml common/
COPY jobs/pom.xml jobs/
RUN mvn -q -B dependency:go-offline -DskipTests || true
COPY . .
COPY --from=frontend /app/api/src/main/resources/static api/src/main/resources/static
RUN mvn -q -B -pl api -am package -DskipTests

# --- Stage 3: runtime ---
FROM eclipse-temurin:21-jre-noble
WORKDIR /app
COPY --from=backend /app/api/target/*.jar app.jar
# Render injects PORT; default keeps local parity with application.yml (9090).
ENV UPLOADS_DIR=/tmp/uploads
EXPOSE 9090
ENTRYPOINT ["sh", "-c", "exec java -Xmx512m -jar app.jar --server.port=${PORT:-9090}"]
