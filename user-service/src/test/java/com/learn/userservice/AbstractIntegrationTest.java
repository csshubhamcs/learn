package com.learn.userservice;

import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

// Spring Boot 4 no longer auto-registers a TestRestTemplate bean for RANDOM_PORT tests;
// it must be requested explicitly via @AutoConfigureTestRestTemplate.
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("userdb")
            .withUsername("test")
            .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // TASK 3 SECURITY TRAP WORKAROUND removed here: Task 6 introduces the real
        // SecurityConfig / SecurityFilterChain bean, so the auto-configuration exclusion
        // that used to blank out security for this test context is gone. Subclasses that
        // need a live issuer-uri (real security assertions) extend AbstractAuthIntegrationTest
        // instead, which starts a Keycloak container and supplies one via @DynamicPropertySource.
    }
}
