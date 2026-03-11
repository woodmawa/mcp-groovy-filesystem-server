package com.softwood.mcp.service.transform

/**
 * Contract for all server-side file transformers.
 *
 * Implementations are Spring @Component beans auto-collected by FileTransformService.
 * Each transformer receives an already-validated, normalised absolute path and the
 * full options map from the MCP call.  It is responsible for reading, modifying, and
 * atomically writing the file.  Path security checks and hash-guard enforcement are
 * handled by FileTransformService before apply() is called.
 *
 * v0.8.2
 */
interface FileTransformer {
    /** Unique identifier used in options.transform (e.g. 'replace_section'). */
    String getName()

    /** Apply the transform to the file at normalizedPath.  Mutates the file on success. */
    TransformResult apply(String normalizedPath, Map<String, Object> options)
}
