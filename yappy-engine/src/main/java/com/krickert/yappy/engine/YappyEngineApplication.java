    // Suggested location: yappy-engine/src/main/java/com/krickert/yappy/engine/YappyEngineApplication.java
    // (Adjust package if your base package for the engine is different)
    package com.krickert.yappy.engine;

    import io.micronaut.runtime.Micronaut;

    public class YappyEngineApplication {
        public static void main(String[] args) {
            Micronaut.run(YappyEngineApplication.class, args);
        }
    }
    