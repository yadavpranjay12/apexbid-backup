package org.vaibhav.apexbid.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WebSocketEventListener {

    private final AtomicInteger activeConnections;

    public WebSocketEventListener(MeterRegistry meterRegistry) {
        this.activeConnections = new AtomicInteger(0);
        Gauge.builder("websocket.connections.active", activeConnections, AtomicInteger::get)
                .description("Number of currently active WebSocket connections")
                .register(meterRegistry);
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        activeConnections.incrementAndGet();
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        if (activeConnections.get() > 0) {
            activeConnections.decrementAndGet();
        }
    }
}
