package com.learn.taskservice.common.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Maps Keycloak's realm_access.roles claim onto Spring authorities. */
@Component
public class JwtRoleConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /** Missing {@code realm_access} (a token with no role claim at all) yields zero authorities, not an error. */
    @Override
    @SuppressWarnings("unchecked")
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        Collection<String> roles =
                realmAccess == null ? List.of() : (Collection<String>) realmAccess.getOrDefault("roles", List.of());

        Set<GrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toUnmodifiableSet());

        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
