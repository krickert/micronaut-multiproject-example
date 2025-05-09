package com.krickert.search.config;

import org.kiwiproject.consul.Consul;
import org.kiwiproject.consul.cache.KVCache;
import org.kiwiproject.consul.model.kv.Value;
import com.google.common.net.HostAndPort; // Assuming Guava is available
import org.kiwiproject.consul.option.Options;


import java.util.Map; // For using the result of getMap()
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.kiwiproject.consul.cache.KVCache.newCache;

public class ConsulWatchExample {

    public static void main(String[] args) throws InterruptedException {
        // 1. Create a Consul client instance
        Consul client = Consul.builder()
                .withHostAndPort(HostAndPort.fromParts("localhost", 8500))
                .build();

        // 2. Define the key you want to watch
        String keyToWatch = "my/application/config";

        int watchSeconds = 5;

        // 3. Create a KVCache for the specific key path
        try (KVCache kvCache = newCache(
                client.keyValueClient(),
                keyToWatch,
                watchSeconds
        )) {

            // 4. Add a listener to the cache to react to changes
            kvCache.addListener(newValues -> {
                System.out.println("Listener invoked. Cache snapshot keys: " + newValues.keySet());
                Value consulApiValue = newValues.get("");

                Optional<String> updatedValueString;
                if (consulApiValue != null) {
                    updatedValueString = consulApiValue.getValueAsString();
                    System.out.println("Key '" + keyToWatch + "' (watched as root) updated. New value: " + updatedValueString.orElse("[empty or null string]"));
                } else {
                    updatedValueString = Optional.empty();
                    System.out.println("Key '" + keyToWatch + "' (watched as root) was deleted or is no longer present.");
                }
            });

            // 5. Start the cache
            System.out.println("Starting Consul KVCache for key (as rootPath): " + keyToWatch);
            kvCache.start();

            // Keep the main thread alive
            System.out.println("Cache started. Waiting for changes... (Press Ctrl+C to stop)");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    TimeUnit.SECONDS.sleep(10);

                    // Attempt to get the whole map and then retrieve the specific key
                    Map<String, Value> currentCacheMap = kvCache.getMap();
                    Optional<Value> optionalConsulApiValue = Optional.ofNullable(currentCacheMap.get(""));


                    Optional<String> currentValueString = optionalConsulApiValue
                            .flatMap(Value::getValueAsString);

                    if (currentValueString.isPresent()) {
                        System.out.println("Current cached value for '" + keyToWatch + "': " + currentValueString.get());
                    } else {
                        if (optionalConsulApiValue.isPresent()) {
                            System.out.println("Current cached value for '" + keyToWatch + "' is present in cache but has an empty/null string value.");
                        } else {
                            System.out.println("Current cached value for '" + keyToWatch + "' is not present in cache (likely deleted).");
                        }
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("Watch interrupted.");
                }
            }

            // 6. Stop the cache when done
            System.out.println("Stopping Consul KVCache.");
            kvCache.stop();
        }
    }
}