# syntax=docker/dockerfile:1

# Étape de compilation : construit le jar exécutable puis l'extrait en couches.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# Les fichiers Gradle d'abord, pour profiter du cache tant que les dépendances ne changent pas.
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN ./gradlew bootJar -x test --no-daemon \
    && java -Djarmode=tools -jar "$(find build/libs -name '*.jar' ! -name '*-plain.jar')" \
        extract --layers --launcher --destination /extracted

# Étape d'exécution : JRE seul, utilisateur non root.
FROM eclipse-temurin:25-jre
# curl est installé explicitement pour le HEALTHCHECK (absent de l'image JRE).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 blinder \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin blinder \
    && mkdir /data \
    && chown 10001:10001 /data

WORKDIR /app
# Couches copiées de la moins à la plus volatile.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./

# Base H2/SQLite éventuelle et base GeoIP facultative (geoip/).
VOLUME /data

ENV SPRING_PROFILES_ACTIVE=prod,postgres \
    JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75

USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent http://localhost:8081/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
