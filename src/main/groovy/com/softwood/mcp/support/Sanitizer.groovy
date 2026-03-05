package com.softwood.mcp.support

import groovy.transform.CompileStatic

/**
 * Shared sanitization utilities for MCP server responses.
 *
 * Removes control characters that would break JSON-RPC over STDIO.
 * Used by both StdioMcpServer and McpController.
 *
 * v0.0.5: Extracted from StdioMcpServer to eliminate duplication.
 */
@CompileStatic
class Sanitizer {

    /**
     * Remove control characters from a string (preserves newlines and tabs)
     */
    static String sanitize(String text) {
        if (!text) return text
        try {
            // Strip ASCII/Latin-1 control characters but PRESERVE all valid Unicode
            // (em-dashes, smart quotes, accented chars, CJK, etc.)
            String cleaned = text.replaceAll(/[\x00-\x08\x0B-\x0C\x0E-\x1F\x7F-\x9F]/, '')
            return cleaned
        } catch (Exception e) {
            return "[sanitization error]"
        }
    }

    /**
     * Recursively sanitize all strings in a nested Map/List structure
     */
    static Object sanitizeObject(Object obj) {
        try {
            if (obj == null) {
                return null
            } else if (obj instanceof String) {
                return sanitize((String) obj)
            } else if (obj instanceof Map) {
                Map result = [:]
                ((Map) obj).each { k, v ->
                    result[sanitizeObject(k)] = sanitizeObject(v)
                }
                return result
            } else if (obj instanceof List) {
                return ((List) obj).collect { sanitizeObject(it) }
            } else {
                return obj
            }
        } catch (Exception e) {
            return "[object sanitization error]"
        }
    }
}
