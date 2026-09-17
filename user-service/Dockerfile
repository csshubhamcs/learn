# syntax=docker/dockerfile:1

# Packages a jar that Gradle has ALREADY built. It does not compile anything.
#
# Why not build inside the image: this is a monorepo, and settings.gradle.kts includes every
# service. A Dockerfile that copied only its own directory could not configure the build at
# all -- Gradle fails with "Configuring project ':other-service' without an existing
# directory is not allowed". Copying every service instead would mean each image rebuilds
# when an unrelated service changes, and CI would compile the same code twice: once in the
# test job, once again here.
#
# So the contract is: build first, then package.
#
#   ./gradlew :user-service:bootJar
#   docker build -f user-service/Dockerfile -t user-service .
#
# If the jar is missing the COPY fails immediately with a clear message, which is the
# behaviour you want -- far better than silently shipping a stale artifact.

# Layers stage: explodes the jar into Spring Boot's layered layout (dependencies,
# spring-boot-loader, snapshot-dependencies, application) so the runtime image can COPY each
# layer separately. A JRE is enough: jarmode tooling ships inside the jar itself.
FROM eclipse-temurin:25-jre AS layers
WORKDIR /layers
COPY user-service/build/libs/user-service.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# Runtime stage: JRE only, non-root, layers ordered least- to most-frequently-changing so a
# code-only change invalidates just the last one and re-pushes kilobytes, not the whole jar.
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --create-home appuser
WORKDIR /app
COPY --from=layers /layers/extracted/dependencies/ ./
COPY --from=layers /layers/extracted/spring-boot-loader/ ./
COPY --from=layers /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers /layers/extracted/application/ ./
USER 10001
EXPOSE 7701
# MaxRAMPercentage (not -Xmx) so the heap sizes itself off the container's cgroup limit
# rather than the host machine's total memory.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"
# Boot 4's layered extract produces a thin, directly-executable app.jar (Main-Class is the
# application class) plus a sibling lib/ wired in through the manifest Class-Path -- so this
# is a plain `-jar`, not a JarLauncher invocation.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
