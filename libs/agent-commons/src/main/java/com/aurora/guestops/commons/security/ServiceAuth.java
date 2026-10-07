package com.aurora.guestops.commons.security;

import com.aurora.guestops.commons.config.GuestOpsProperties;
import com.aurora.guestops.commons.config.GuestOpsProperties.AuthMode;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Produces the Authorization header for outbound service-to-service calls (A2A, MCP, registry).
 *
 * <p>Locally every service shares one bearer token. On Cloud Run each call carries a Google-signed
 * ID token whose audience is the target service URL; Cloud Run IAM checks it before the request
 * reaches the application, so the caller's service account needs {@code roles/run.invoker}.
 */
@Component
public class ServiceAuth {

    private final GuestOpsProperties.Auth auth;
    private final Map<String, IdTokenCredentials> idTokenCache = new ConcurrentHashMap<>();

    public ServiceAuth(GuestOpsProperties props) {
        this.auth = props.auth();
    }

    public AuthMode mode() {
        return auth.mode();
    }

    public Optional<String> authorizationHeader(String targetUrl) {
        return switch (auth.mode()) {
            case NONE -> Optional.empty();
            case SHARED_TOKEN -> Optional.of("Bearer " + auth.sharedToken());
            case GOOGLE_ID_TOKEN -> Optional.of("Bearer " + idToken(audienceOf(targetUrl)));
        };
    }

    /** Validates an inbound header in SHARED_TOKEN mode. Other modes rely on the platform (Cloud Run IAM). */
    public boolean isValidInbound(String authorizationHeader) {
        if (auth.mode() != AuthMode.SHARED_TOKEN) {
            return true;
        }
        return ("Bearer " + auth.sharedToken()).equals(authorizationHeader);
    }

    private String idToken(String audience) {
        IdTokenCredentials creds = idTokenCache.computeIfAbsent(audience, aud -> {
            try {
                GoogleCredentials source = GoogleCredentials.getApplicationDefault();
                if (!(source instanceof IdTokenProvider provider)) {
                    throw new IllegalStateException("Application Default Credentials cannot mint ID tokens. "
                            + "On Cloud Run this works automatically; locally use SHARED_TOKEN mode.");
                }
                return IdTokenCredentials.newBuilder().setIdTokenProvider(provider).setTargetAudience(aud).build();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            creds.refreshIfExpired();
            return creds.getIdToken().getTokenValue();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not mint ID token for " + audience, e);
        }
    }

    /** Cloud Run expects the service root URL as the audience, without path or query. */
    static String audienceOf(String url) {
        URI uri = URI.create(url);
        return uri.getScheme() + "://" + uri.getAuthority();
    }
}
