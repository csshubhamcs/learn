package com.learn.userservice.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.learn.userservice.auth.exception.ApiError;
import com.learn.userservice.auth.exception.DuplicateIdentifierException;
import com.learn.userservice.auth.exception.ErrorCode;
import com.learn.userservice.auth.exception.GlobalExceptionHandler;
import com.learn.userservice.auth.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsApplicationExceptionsGenericallyFromTheirErrorCode() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        ResponseEntity<ApiError> response = handler.handleBase(
                new DuplicateIdentifierException("Email already registered"), new ServletWebRequest(request));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().error()).isEqualTo(ErrorCode.DUPLICATE_IDENTIFIER.name());
        assertThat(response.getBody().message()).isEqualTo("Email already registered");
        assertThat(response.getBody().path()).isEqualTo("/api/v1/auth/register");
    }

    @Test
    void neverLeaksInternalDetailOnUnexpectedFailures() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");
        ResponseEntity<ApiError> response = handler.handleUnexpected(
                new IllegalStateException("connection pool exhausted at com.zaxxer.hikari"),
                new ServletWebRequest(request));

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().message()).doesNotContain("hikari");
    }

    @Test
    void mapsAnUnknownPathToNotFoundInsteadOfAServerError() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/no/such/path");
        ResponseEntity<ApiError> response = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "/no/such/path", "/no/such/path"),
                new ServletWebRequest(request));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody().error()).isEqualTo(ErrorCode.NOT_FOUND.name());
    }

    @Test
    void mapsAPurposeBuiltInvalidRequestToAValidationErrorAndKeepsItsMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        ResponseEntity<ApiError> response = handler.handleBase(
                new InvalidRequestException("sortDir must be asc or desc but was 'sideways'"),
                new ServletWebRequest(request));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().error()).isEqualTo(ErrorCode.VALIDATION_FAILED.name());
        assertThat(response.getBody().message()).isEqualTo("sortDir must be asc or desc but was 'sideways'");
    }

    /**
     * I10. A bare IllegalArgumentException from a library is a bug in this service, not a
     * client error. The old blanket handler answered 400 and echoed getMessage() verbatim,
     * which both mislabelled the failure and leaked whatever internal text it carried.
     */
    @Test
    void aLibraryThrownIllegalArgumentIsAServerErrorAndItsMessageIsNotEchoed() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ExplodingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        MvcResult result = mvc.perform(get("/explode")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString())
                .contains(ErrorCode.INTERNAL_ERROR.name())
                .doesNotContain("jdbc:postgresql://internal-host");
    }

    @RestController
    static class ExplodingController {

        @GetMapping("/explode")
        String explode() {
            throw new IllegalArgumentException("Invalid URI jdbc:postgresql://internal-host/userdb");
        }
    }
}
