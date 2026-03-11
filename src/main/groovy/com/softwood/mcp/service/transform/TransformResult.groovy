package com.softwood.mcp.service.transform

import groovy.transform.Canonical
import groovy.transform.CompileStatic

/**
 * Immutable result returned by FileTransformer.apply().
 *
 * On success: success=true, linesAffected, message set.
 * On failure: success=false, error (human-readable), hint (optional context for retry).
 *
 * v0.8.2
 */
@Canonical
@CompileStatic
class TransformResult {
    boolean success
    int     linesAffected = 0
    String  message       = ''
    String  error         = ''
    String  hint          = ''
}
