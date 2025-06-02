package com.krickert.search.pipeline.module;

import io.micronaut.context.annotation.Bean;
import io.micronaut.core.annotation.Introspected;

import java.lang.annotation.*;

/**
 * Marks a class as a Yappy module that will be automatically discovered
 * and registered by the engine.
 * 
 * Example usage:
 * <pre>
 * {@code
 * @YappyModule(
 *     name = "text-chunker",
 *     version = "1.0",
 *     description = "Splits text into chunks for processing"
 * )
 * @Singleton
 * public class TextChunkerModule implements SimplePipeStepProcessor {
 *     // implementation
 * }
 * }
 * </pre>
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Bean
@Introspected
public @interface YappyModule {
    
    /**
     * The unique name of this module.
     * This is used as the service name in Consul and for routing.
     */
    String name();
    
    /**
     * The version of this module.
     * Used for compatibility checking and upgrades.
     */
    String version() default "1.0";
    
    /**
     * A description of what this module does.
     */
    String description() default "";
    
    /**
     * Tags for categorizing this module (e.g., "nlp", "parsing", "transformation").
     */
    String[] tags() default {};
    
    /**
     * Whether this module should be automatically tested before going live.
     * Default is true for safety.
     */
    boolean autoTest() default true;
    
    /**
     * The priority of this module when multiple implementations are available.
     * Higher values mean higher priority.
     */
    int priority() default 0;
    
    /**
     * Whether this module supports parallel processing.
     */
    boolean supportsParallel() default true;
}