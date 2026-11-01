package com.substring.chat.New_Chatapp_backend.config;

import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class MongoConfig {

    /**
     * Optimizes MongoDB connection pooling and network timeouts.
     * Reuses connections in a warm pool rather than reconnecting per-request,
     * and bounds cluster selection and socket timeouts to prevent indefinite hanging.
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer mongoClientSettingsCustomizer() {
        return clientSettingsBuilder -> {
            clientSettingsBuilder.applyToConnectionPoolSettings(pool ->
                    pool.minSize(5)
                        .maxSize(50)
                        .maxWaitTime(5, TimeUnit.SECONDS)
                        .maxConnectionIdleTime(60, TimeUnit.SECONDS)
            );
            clientSettingsBuilder.applyToSocketSettings(socket ->
                    socket.connectTimeout(5, TimeUnit.SECONDS)
                          .readTimeout(10, TimeUnit.SECONDS)
            );
            clientSettingsBuilder.applyToClusterSettings(cluster ->
                    cluster.serverSelectionTimeout(5, TimeUnit.SECONDS)
            );
        };
    }
}
