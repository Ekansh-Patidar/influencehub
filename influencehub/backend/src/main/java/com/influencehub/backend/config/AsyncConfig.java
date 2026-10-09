package com.influencehub.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Enables @Async observers (uses Spring Boot's auto-configured applicationTaskExecutor pool). */
@Configuration
@EnableAsync
public class AsyncConfig {
}
