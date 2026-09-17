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
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Request correlation (Task 5): reuse the real W3C trace id Micrometer Tracing puts in
    // MDC instead of minting a service-local id that stops meaning anything past this
    // service's boundary. Declared as `implementation`, not the brief's `api` - this module
    // only applies the `java` plugin (see root build.gradle.kts), not `java-library`, so
    // `api` is not a configuration that exists here, and nothing downstream consumes this
    // module as a library anyway.
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("org.liquibase:liquibase-core")
    // Spring Boot 4 split LiquibaseAutoConfiguration out of spring-boot-autoconfigure into
    // this dedicated module (same relocation pattern as spring-boot-persistence /
    // spring-boot-security). Without it, no SpringLiquibase bean is created and no
    // migration ever runs, even though liquibase-core itself is present and Hibernate's
    // ddl-auto=validate correctly detects the missing tables.
    implementation("org.springframework.boot:spring-boot-liquibase")
    implementation(libs.keycloak.admin.client)
    implementation(libs.springdoc)
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation(libs.keycloak.testcontainers)
    // Backs ArchitectureTest, which enforces the module boundary the whole design rests on:
    // auth must never depend on profile, so deleting profile cannot break auth.
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
    // annotationProcessor, so mappers under src/test/java need these repeated here
    // (discovered while proving APT actually runs via the test-tree smoke test).
    testAnnotationProcessor(libs.mapstruct.processor)
    testAnnotationProcessor(libs.lombok.mapstruct.binding)

    // JSON logs in every profile except local (Task 15). logstash-logback-encoder is not in
    // gradle/libs.versions.toml (out of scope for this change - see docker-migration-report.md),
    // so the version is pinned here directly.
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // genMigration (Task 14): diffs the JPA entities against a running database via
    // liquibase-hibernate7. This configuration is intentionally separate from the app's own
    // runtime liquibase-core/spring-boot-liquibase dependencies above - it is a build-time
    // tool classpath, not something that ships in the jar.
    liquibaseRuntime("org.liquibase:liquibase-core")
    liquibaseRuntime(libs.liquibase.hibernate)
    liquibaseRuntime("org.postgresql:postgresql")
    liquibaseRuntime("info.picocli:picocli:4.7.6")
    liquibaseRuntime("org.springframework.boot:spring-boot-starter-data-jpa")
    liquibaseRuntime(sourceSets.main.get().output)
}

springBoot { mainClass.set("com.learn.userservice.UserServiceApplication") }

// Task 6: makes the realm definition available to Testcontainers-backed integration tests
// (AbstractAuthIntegrationTest) without keeping a second copy in sync. One source of truth:
// the file docker-compose also mounts for the local Keycloak container.
tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processTestResources") {
    from(rootProject.file("keycloak")) { include("realm-export.json") }
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("user-service.jar")
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

// Task 14: genMigration - diffs the JPA entities (referenceUrl below) against a running
// database (url below) and writes the difference as a new changelog file, so adding a
// field is "edit the entity, run this, review, commit" instead of writing SQL by hand.
val migrationName: String = (project.findProperty("name") as String?) ?: "change"

liquibase {
    activities.register("diff") {
        this.arguments =
            mapOf(
                "changelogFile" to
                    "src/main/resources/db/changelog/generated/${System.currentTimeMillis()}-$migrationName.yaml",
                "url" to (System.getenv("DB_URL") ?: "jdbc:postgresql://localhost:5432/userdb"),
                "username" to (System.getenv("DB_USERNAME") ?: "postgres"),
                "password" to (System.getenv("DB_PASSWORD") ?: "postgres"),
                // Package is com.learn.userservice (this project's actual package root),
                // not the placeholder package name used in the original task brief.
                "referenceUrl" to
                    ("hibernate:spring:com.learn.userservice" +
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
    doLast {
        println("Generated changelog under src/main/resources/db/changelog/generated/")
        println(
            "Review it, then add an include for db/changelog/generated/ to " +
                "db.changelog-master.yaml if this is the first generated migration " +
                "(that file is out of scope for this change - see docker-migration-report.md)."
        )
    }
}
