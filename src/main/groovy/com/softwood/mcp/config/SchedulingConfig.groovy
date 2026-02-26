package com.softwood.mcp.config

import groovy.transform.CompileStatic
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Enables Spring's @Scheduled task support.
 * Used by UsageTracker for periodic SQLite flush.
 */
@Configuration
@EnableScheduling
@CompileStatic
class SchedulingConfig {
}
