package com.learn.userservice.auth.service;

import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Normalizes login identifiers so uniqueness is decided on one canonical form. Without this,
 * {@code " Alice@Example.com"} and {@code "alice@example.com"} would be treated as different
 * identifiers by a database unique constraint, letting the same real address register twice.
 */
@Component
public class IdentifierNormalizer {

    /** Trims and lowercases; case and surrounding whitespace never distinguish two emails. */
    public String normalizeEmail(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }
}
