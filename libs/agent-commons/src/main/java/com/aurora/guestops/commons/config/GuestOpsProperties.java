package com.aurora.guestops.commons.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Platform-wide settings shared by every service. Each service sets its own name, public URL and
 * network zone; the rest usually comes from environment variables.
 */
@ConfigurationProperties(prefix = "guestops")
public record GuestOpsProperties(
        @DefaultValue("unnamed-service") String serviceName,
        @DefaultValue("http://localhost:8080") String publicBaseUrl,
        @DefaultValue("core") String networkZone,
        @DefaultValue Auth auth,
        @DefaultValue Registry registry,
        @DefaultValue Models models,
        @DefaultValue Mcp mcp) {

    public enum AuthMode {
        /** No service-to-service authentication (local experiments only). */
        NONE,
        /** A shared bearer token. Default for local development. */
        SHARED_TOKEN,
        /** Google-signed ID tokens, verified by Cloud Run IAM in front of the service. */
        GOOGLE_ID_TOKEN
    }

    public record Auth(@DefaultValue("SHARED_TOKEN") AuthMode mode,
                       @DefaultValue("local-dev-token-change-me") String sharedToken) {
    }

    public record Registry(@DefaultValue("http://localhost:8090") String url,
                           @DefaultValue("false") boolean selfRegister,
                           @DefaultValue("30") int heartbeatSeconds,
                           @DefaultValue("30") int policyCacheSeconds) {
    }

    /** Model tiers: FAST for routing and safety, STANDARD for specialists, JUDGE for evaluation. */
    public record Models(@DefaultValue("gemini-2.5-flash-lite") String fast,
                         @DefaultValue("gemini-2.5-flash") String standard,
                         @DefaultValue("gemini-2.5-pro") String judge) {
    }

    /** The MCP server to use, looked up in the MCP registry by id; fallbackUrl is used if the registry is down. */
    public record Mcp(@DefaultValue("hotel-mcp-server") String serverId,
                      @DefaultValue("") String fallbackUrl,
                      @DefaultValue("/mcp") String endpoint) {
    }
}
