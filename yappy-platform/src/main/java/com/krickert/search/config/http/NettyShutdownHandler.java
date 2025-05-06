package com.krickert.search.config.http;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.BeanCreatedEvent;
import io.micronaut.context.event.BeanCreatedEventListener;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Handles graceful shutdown of Netty event executors to prevent "executor not accepting a task" errors.
 * This class tracks all created Netty event executors and ensures they are properly shut down
 * before the application exits.
 */
@Singleton
@Context // Ensure this bean is loaded eagerly
@Requires(property = "micronaut.server.netty.worker.shutdown-quiet-period")
public class NettyShutdownHandler implements BeanCreatedEventListener<EventExecutorGroup> {

    private static final Logger LOG = LoggerFactory.getLogger(NettyShutdownHandler.class);
    
    private final List<EventExecutorGroup> executorGroups = new ArrayList<>();

    /**
     * Tracks all created Netty event executor groups.
     *
     * @param event the bean created event
     * @return the event executor group
     */
    @Override
    public EventExecutorGroup onCreated(BeanCreatedEvent<EventExecutorGroup> event) {
        EventExecutorGroup executorGroup = event.getBean();
        if (executorGroup != null) {
            executorGroups.add(executorGroup);
            LOG.debug("Tracking Netty EventExecutorGroup: {}", executorGroup);
        }
        return executorGroup;
    }

    /**
     * Handles the ServerShutdownEvent by gracefully shutting down all Netty event executor groups.
     * This ensures that all resources are released properly before the application shuts down.
     *
     * @param event the server shutdown event
     */
    @EventListener
    public void onServerShutdown(ServerShutdownEvent event) {
        LOG.info("Server shutdown event received, shutting down {} Netty event executor groups", executorGroups.size());
        
        // Create a default executor group for shutdown tasks if needed
        DefaultEventExecutorGroup shutdownGroup = new DefaultEventExecutorGroup(1);
        
        for (EventExecutorGroup executorGroup : executorGroups) {
            try {
                if (executorGroup != null && !executorGroup.isShutdown() && !executorGroup.isTerminated()) {
                    LOG.debug("Shutting down Netty EventExecutorGroup: {}", executorGroup);
                    
                    // Schedule shutdown on a separate executor to avoid deadlocks
                    shutdownGroup.execute(() -> {
                        try {
                            // First request graceful shutdown
                            executorGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS);
                            
                            // Wait for termination
                            if (!executorGroup.awaitTermination(5, TimeUnit.SECONDS)) {
                                LOG.warn("Netty EventExecutorGroup did not terminate in time: {}", executorGroup);
                            } else {
                                LOG.debug("Netty EventExecutorGroup terminated successfully: {}", executorGroup);
                            }
                        } catch (Exception e) {
                            LOG.warn("Error shutting down Netty EventExecutorGroup: {}", executorGroup, e);
                        }
                    });
                }
            } catch (Exception e) {
                LOG.warn("Error initiating shutdown of Netty EventExecutorGroup: {}", executorGroup, e);
            }
        }
        
        // Shutdown the shutdown group itself
        try {
            shutdownGroup.shutdownGracefully(1, 5, TimeUnit.SECONDS);
            if (!shutdownGroup.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("Shutdown executor group did not terminate in time");
            }
        } catch (Exception e) {
            LOG.warn("Error shutting down the shutdown executor group", e);
        }
        
        LOG.info("All Netty event executor groups shutdown process completed");
    }
}