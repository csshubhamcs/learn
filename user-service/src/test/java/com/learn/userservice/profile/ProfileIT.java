package com.learn.userservice.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.learn.userservice.AbstractAuthIntegrationTest;
import com.learn.userservice.auth.dto.request.RegisterRequest;
import com.learn.userservice.auth.dto.response.UserResponse;
import com.learn.userservice.auth.service.KeycloakService;
import com.learn.userservice.profile.dto.request.UpdateProfileRequest;
import com.learn.userservice.profile.dto.response.ProfileResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

class ProfileIT extends AbstractAuthIntegrationTest {

    private static final String PROFILE = "/api/v1/users/me/profile";

    @Autowired
    KeycloakService keycloak;

    private String registerAndLogin(String email) {
        rest.postForEntity("/api/v1/auth/register", new RegisterRequest(email, "Password123!"), UserResponse.class);
        return tokens().passwordGrant(email, "Password123!");
    }

    /** Builds a patch request with every field null except the ones named. */
    private UpdateProfileRequest patch(
            String displayName, String headline, String bio, String instagramUrl, String linkedinUrl) {
        return new UpdateProfileRequest(
                displayName,
                headline,
                bio,
                null, // avatarUrl
                null, // dateOfBirth
                null, // gender
                null, // addressLine1
                null, // addressLine2
                null, // city
                null, // state
                null, // country
                null, // postalCode
                null, // locale
                null, // timezone
                instagramUrl,
                linkedinUrl,
                null, // websiteUrl
                null); // xUrl
    }

    /** Builds a patch that touches only {@code country}. */
    private UpdateProfileRequest countryPatch(String country) {
        return new UpdateProfileRequest(
                null, null, null, null, null, null, null, null, null, null, country, null, null, null, null, null, null,
                null);
    }

    /**
     * C3. The lazy profile insert is keyed on the JWT subject and fk_profile_user needs a
     * matching users row, so a Keycloak account created outside registration - admin console,
     * realm import, a future federated IdP - used to hit an integrity violation on its very
     * first profile read and get a 500. It must answer what /users/me already answers: 404.
     */
    @Test
    void aPrincipalWithNoLocalAccountRowGets404FromTheProfileNotAServerError() {
        String email = "no-local-row-" + UUID.randomUUID() + "@example.com";
        UUID keycloakOnly = keycloak.createUser(email, "Password123!");
        try {
            String token = tokens().passwordGrant(email, "Password123!");

            ResponseEntity<String> account =
                    rest.exchange("/api/v1/users/me", HttpMethod.GET, bearer(token), String.class);
            assertThat(account.getStatusCode().value()).isEqualTo(404);

            ResponseEntity<String> profile = rest.exchange(PROFILE, HttpMethod.GET, bearer(token), String.class);
            assertThat(profile.getStatusCode().value()).isEqualTo(404);
            assertThat(profile.getBody()).contains("NOT_FOUND");
        } finally {
            keycloak.deleteUser(keycloakOnly);
        }
    }

    /**
     * I3. Disabling the Keycloak account only stops *new* tokens being issued; the access
     * token the user already holds stays signature-valid, and nothing in the request path
     * used to look at account status - so a self-deleted user could keep reading and writing
     * personal data for the rest of that token's lifetime.
     */
    @Test
    void aSelfDeletedUsersStillValidTokenCanNoLongerReadOrWriteTheProfile() {
        String token = registerAndLogin("deleted-then-writes-" + UUID.randomUUID() + "@example.com");

        ResponseEntity<String> beforeDelete = rest.exchange(
                PROFILE, HttpMethod.PATCH, bearer(token, patch("Before", null, null, null, null)), String.class);
        assertThat(beforeDelete.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Void> deleted = rest.exchange("/api/v1/users/me", HttpMethod.DELETE, bearer(token), Void.class);
        assertThat(deleted.getStatusCode().value()).isEqualTo(204);

        ResponseEntity<String> write = rest.exchange(
                PROFILE,
                HttpMethod.PATCH,
                bearer(token, patch("still here after deletion", null, null, null, null)),
                String.class);
        assertThat(write.getStatusCode().value()).isEqualTo(404);

        ResponseEntity<String> read = rest.exchange(PROFILE, HttpMethod.GET, bearer(token), String.class);
        assertThat(read.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void returnsAnEmptyProfileOnFirstRead() {
        String token = registerAndLogin("prof-" + UUID.randomUUID() + "@example.com");

        ResponseEntity<ProfileResponse> response =
                rest.exchange("/api/v1/users/me/profile", HttpMethod.GET, bearer(token), ProfileResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().displayName()).isNull();
        assertThat(response.getBody().instagramUrl()).isNull();
    }

    @Test
    void patchSetsOnlyTheFieldsSupplied() {
        String token = registerAndLogin("prof2-" + UUID.randomUUID() + "@example.com");

        ResponseEntity<ProfileResponse> response = rest.exchange(
                "/api/v1/users/me/profile",
                HttpMethod.PATCH,
                bearer(token, patch("Shubham", "Engineer", null, null, null)),
                ProfileResponse.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().displayName()).isEqualTo("Shubham");
        assertThat(response.getBody().headline()).isEqualTo("Engineer");
        assertThat(response.getBody().bio()).isNull();
    }

    @Test
    void aSecondPatchChangesOnlyItsFieldAndLeavesEarlierFieldsIntact() {
        String token = registerAndLogin("prof3-" + UUID.randomUUID() + "@example.com");

        rest.exchange(
                "/api/v1/users/me/profile",
                HttpMethod.PATCH,
                bearer(token, patch("Shubham", "Engineer", null, null, null)),
                ProfileResponse.class);

        ResponseEntity<ProfileResponse> after = rest.exchange(
                "/api/v1/users/me/profile",
                HttpMethod.PATCH,
                bearer(token, patch(null, null, "Builds things", null, null)),
                ProfileResponse.class);

        assertThat(after.getBody().displayName()).isEqualTo("Shubham"); // preserved
        assertThat(after.getBody().headline()).isEqualTo("Engineer"); // preserved
        assertThat(after.getBody().bio()).isEqualTo("Builds things"); // applied
    }

    @Test
    void settingASocialLinkWorksLikeAnyOtherField() {
        String token = registerAndLogin("prof4-" + UUID.randomUUID() + "@example.com");

        rest.exchange(
                "/api/v1/users/me/profile",
                HttpMethod.PATCH,
                bearer(token, patch("Shubham", null, null, null, null)),
                ProfileResponse.class);

        ResponseEntity<ProfileResponse> after = rest.exchange(
                "/api/v1/users/me/profile",
                HttpMethod.PATCH,
                bearer(
                        token,
                        patch(null, null, null, "https://instagram.com/shubham", "https://linkedin.com/in/shubham")),
                ProfileResponse.class);

        assertThat(after.getBody().displayName()).isEqualTo("Shubham"); // preserved
        assertThat(after.getBody().instagramUrl()).isEqualTo("https://instagram.com/shubham");
        assertThat(after.getBody().linkedinUrl()).isEqualTo("https://linkedin.com/in/shubham");
    }

    @Test
    void countryCanBeSetAndThenClearedWithABlank() {
        String token = registerAndLogin("prof5-" + UUID.randomUUID() + "@example.com");

        ResponseEntity<ProfileResponse> set =
                rest.exchange(PROFILE, HttpMethod.PATCH, bearer(token, countryPatch("IN")), ProfileResponse.class);
        assertThat(set.getStatusCode().value()).isEqualTo(200);
        assertThat(set.getBody().country()).isEqualTo("IN");

        // A null would mean "leave unchanged", so blank is the only way to erase it. Under
        // @Size(min = 2, max = 2) this was a 400 and the field could never be emptied again.
        ResponseEntity<ProfileResponse> cleared =
                rest.exchange(PROFILE, HttpMethod.PATCH, bearer(token, countryPatch("")), ProfileResponse.class);
        assertThat(cleared.getStatusCode().value()).isEqualTo(200);
        assertThat(cleared.getBody().country()).isEmpty();
    }

    @Test
    void countryStillRejectsAnythingThatIsNotATwoLetterCode() {
        String token = registerAndLogin("prof6-" + UUID.randomUUID() + "@example.com");

        for (String bad : new String[] {"I", "IND", "12", "I1"}) {
            ResponseEntity<String> response =
                    rest.exchange(PROFILE, HttpMethod.PATCH, bearer(token, countryPatch(bad)), String.class);
            assertThat(response.getStatusCode().value())
                    .as("country %s must be rejected", bad)
                    .isEqualTo(400);
        }
    }
}
