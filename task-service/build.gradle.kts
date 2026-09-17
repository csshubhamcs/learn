// The org.liquibase.gradle plugin jar (see gradle/libs.versions.toml: liquibaseGradle)
// declares zero dependencies of its own - not even liquibase-core - so `liquibase.Scope`,
// which its own LiquibasePlugin.apply()/ArgumentBuilder call directly at *plugin-apply*
// time (before any project configuration, including `liquibaseRuntime` below, is ever
// resolved), is missing from the plugin's classloader and apply() throws
// NoClassDefFoundError unless liquibase-core is also put on the buildscript classpath here.
buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("org.liquibase:liquibase-core:5.0.3") }
}

plugins {
    java
    alias(libs.plugins.springBoot)
    alias(libs.plugins.spotless)
    alias(libs.plugins.liquibase)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // Resource server only. Unlike user-service there is deliberately no
    // keycloak-admin-client here: this service never writes to Keycloak and never calls it
    // on the request path - it validates every JWT locally against the realm's cached JWKS,
    // so it needs no client secret and survives Keycloak being down.
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Request correlation: reuse the real W3C trace id Micrometer Tracing puts in MDC, which
    // GlobalExceptionHandler reads back into ApiError.requestId.
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("org.liquibase:liquibase-core")
    // Spring Boot 4 split LiquibaseAutoConfiguration out of spring-boot-autoconfigure into
    // this dedicated module. Without it, no SpringLiquibase bean is created and no migration
    // ever runs, even though liquibase-core itself is present and the app starts cleanly -
    // the failure only surfaces later as Hibernate's ddl-auto=validate reporting a missing
    // table.
    implementation("org.springframework.boot:spring-boot-liquibase")
    implementation(libs.springdoc)
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation(libs.keycloak.testcontainers)
    // Backs ArchitectureTest, which enforces the layering the conventions rest on:
    // controllers never reach past the service layer into a repository, and the shared
    // `common` package never depends on the `task` feature.
    testImplementation(libs.archunit.junit5)
    // Spring Boot 4 moved TestRestTemplate (and its autoconfiguration) out of
    // spring-boot-test into this dedicated artifact, which in turn needs
    // RestTemplateBuilder from spring-boot-restclient.
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)

    implementation(libs.mapstruct)
    annotationProcessor(libs.mapstruct.processor)
    // REQUIRED: makes Lombok run before MapStruct. Without it MapStruct sees a class with
    // no getters yet and silently generates an empty mapper.
    annotationProcessor(libs.lombok.mapstruct.binding)

    // Gradle's testAnnotationProcessor configuration does NOT inherit from
    // annotationProcessor, so anything under src/test/java that needs APT needs these
    // repeated here.
    testAnnotationProcessor(libs.mapstruct.processor)
    testAnnotationProcessor(libs.lombok.mapstruct.binding)

    // JSON logs in every profile except local. logstash-logback-encoder is not in
    // gradle/libs.versions.toml, so the version is pinned here directly, as in user-service.
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // genMigration: diffs the JPA entities against a running database via
    // liquibase-hibernate7. A build-time tool classpath, not something that ships in the jar.
    liquibaseRuntime("org.liquibase:liquibase-core")
    liquibaseRuntime(libs.liquibase.hibernate)
    liquibaseRuntime("org.postgresql:postgresql")
    liquibaseRuntime("info.picocli:picocli:4.7.6")
    liquibaseRuntime("org.springframework.boot:spring-boot-starter-data-jpa")
    liquibaseRuntime(sourceSets.main.get().output)
}

springBoot { mainClass.set("com.learn.taskservice.TaskServiceApplication") }

// Makes the realm definition available to the Testcontainers-backed integration tests
// without keeping a second copy in sync: the same file docker-compose mounts for the local
// Keycloak container is the one the test container imports.
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processTestResources") {
    from(rootProject.file("keycloak")) { include("realm-export.json") }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("task-service.jar")
    layered { enabled.set(true) }
}

spotless {
    java {
        target("src/**/*.java")
        // palantir 2.50.0 throws NoSuchMethodError on Java 25; 2.98.0 is fine.
        // googleJavaFormat does not work on Java 25 at all.
        palantirJavaFormat("2.98.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

// genMigration - diffs the JPA entities (referenceUrl below) against a running database
// (url below) and writes the difference as a new changelog file.
val migrationName: String = (project.findProperty("name") as String?) ?: "change"

liquibase {
    activities.register("diff") {
        this.arguments =
            mapOf(
                "changelogFile" to
                    "src/main/resources/db/changelog/generated/${System.currentTimeMillis()}-$migrationName.yaml",
                "url" to (System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/taskdb"),
                "username" to (System.getenv("DB_USERNAME") ?: "postgres"),
                "password" to (System.getenv("DB_PASSWORD") ?: "postgres"),
                "referenceUrl" to
                    ("hibernate:spring:com.learn.taskservice" +
                        "?dialect=org.hibernate.dialect.PostgreSQLDialect" +
                        "&hibernate.physical_naming_strategy=" +
                        "org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy"),
            )
    }
    runList = "diff"
}

tasks.register("genMigration") {
    group = "database"
    description = "Diffs JPA entities against the running database and writes a changelog"
    dependsOn("diffChangeLog")
    doLast { println("Generated changelog under src/main/resources/db/changelog/generated/") }
}
