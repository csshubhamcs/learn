package com.learn.taskservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.learn.taskservice.task.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.ResponseEntity;

class ApplicationStartupTest extends AbstractAuthIntegrationTest {

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    TaskRepository tasks;

    @Test
    void contextLoadsAndLivenessIsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health/liveness", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("UP");
    }

    /**
     * Proves the Liquibase migration actually ran. Without
     * {@code org.springframework.boot:spring-boot-liquibase} on the classpath, Boot 4 creates
     * no SpringLiquibase bean at all and the app still starts cleanly - so "the context
     * loaded" is not evidence the schema exists. Querying the table is.
     */
    @Test
    void theTaskTableExists() {
        assertThat(tasks.count()).isGreaterThanOrEqualTo(0);
    }
}
