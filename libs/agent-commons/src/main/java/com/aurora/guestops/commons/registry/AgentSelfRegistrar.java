package com.aurora.guestops.commons.registry;

import com.aurora.guestops.commons.a2a.AgentCards;
import com.aurora.guestops.commons.agent.AgentCatalog;
import com.aurora.guestops.commons.agent.AgentDefinition;
import com.aurora.guestops.commons.config.GuestOpsProperties;
import com.aurora.guestops.commons.registry.RegistryModels.AgentRegistration;
import com.aurora.guestops.commons.registry.RegistryModels.Protocol;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Registers this service's agents in the agent registry once the web server is up, then sends
 * heartbeats. If the registry restarts and forgets an agent, the next heartbeat re-registers it.
 * A2A agents register their Agent Card URL; the registry fetches and validates the card itself.
 */
@Component
@ConditionalOnProperty(name = "guestops.registry.self-register", havingValue = "true")
public class AgentSelfRegistrar {

    private static final Logger log = LoggerFactory.getLogger(AgentSelfRegistrar.class);

    private final AgentCatalog catalog;
    private final RegistryClient registry;
    private final GuestOpsProperties props;
    private final boolean a2aServer;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final Set<String> registered = ConcurrentHashMap.newKeySet();

    public AgentSelfRegistrar(AgentCatalog catalog, RegistryClient registry, GuestOpsProperties props,
                              @Value("${guestops.a2a.server-enabled:false}") boolean a2aServer) {
        this.catalog = catalog;
        this.registry = registry;
        this.props = props;
        this.a2aServer = a2aServer;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        ready.set(true);
        tick();
    }

    @Scheduled(fixedDelayString = "#{${guestops.registry.heartbeat-seconds:30} * 1000}", initialDelay = 5_000)
    public void tick() {
        if (!ready.get()) {
            return;
        }
        for (AgentDefinition def : catalog.all()) {
            try {
                if (!registered.contains(def.id()) || !registry.heartbeat(def.id())) {
                    registry.register(registration(def));
                    registered.add(def.id());
                    log.info("Registered agent {} ({})", def.id(), a2aServer ? "A2A" : "in-process");
                }
            } catch (RuntimeException e) {
                registered.remove(def.id());
                log.warn("Registry not reachable for {}: {}", def.id(), e.getMessage());
            }
        }
    }

    private AgentRegistration registration(AgentDefinition def) {
        String base = props.publicBaseUrl();
        return new AgentRegistration(def.id(), def.name(), def.description(),
                def.version() == null ? "1.0.0" : def.version(), def.ownerTeam(), props.serviceName(),
                props.networkZone(), a2aServer ? Protocol.A2A : Protocol.IN_PROCESS,
                a2aServer ? AgentCards.agentUrl(base, def.id()) : null,
                a2aServer ? AgentCards.cardUrl(base, def.id()) : null,
                def.skillTags(), def.riskLevel() == null ? "LOW" : def.riskLevel().name(), null);
    }
}
