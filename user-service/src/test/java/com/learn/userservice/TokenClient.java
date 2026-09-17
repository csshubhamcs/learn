package com.learn.userservice;

import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

public final class TokenClient {

    private final String tokenEndpoint;

    public TokenClient(String authServerUrl, String realm) {
        this.tokenEndpoint = authServerUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    @SuppressWarnings("unchecked")
    public String passwordGrant(String username, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", "web-app");
        form.add("grant_type", "password");
        form.add("username", username);
        form.add("password", password);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        Map<String, Object> body = RestClient.create()
                .post()
                .uri(tokenEndpoint)
                .headers(h -> h.addAll(headers))
                .body(new HttpEntity<>(form, headers).getBody())
                .retrieve()
                .body(Map.class);

        return (String) body.get("access_token");
    }
}
