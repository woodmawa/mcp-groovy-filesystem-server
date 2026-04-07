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
        // v0.8.40: force UTF-8 on stdout so JsonRpcWriter.System.out.println correctly
        // encodes U+2192 and other non-Latin-1 Unicode. Default JVM charset on Windows
        // is Cp1252 which corrupts these chars in tool responses.
        System.setOut(new java.io.PrintStream(System.out, true, 'UTF-8'))

        System.setProperty("spring.main.banner-mode", "off")
        SpringApplication.run(McpGroovyFileSystemServerApplication, args)
    }
}
