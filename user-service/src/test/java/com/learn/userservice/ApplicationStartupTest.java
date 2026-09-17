package com.learn.userservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.ResponseEntity;

class ApplicationStartupTest extends AbstractIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Test
    void contextLoadsAndLivenessIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health/liveness", String.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("UP");
    }
}
