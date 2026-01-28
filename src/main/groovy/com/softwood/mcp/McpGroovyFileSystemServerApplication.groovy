package com.softwood.mcp

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
@Slf4j
@CompileStatic
class McpGroovyFileSystemServerApplication {
    
    static void main(String[] args) {
        SpringApplication.run(McpGroovyFileSystemServerApplication, args)
    }
}
