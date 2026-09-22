package com.softwood.mcp.script

import groovy.transform.CompileStatic
import org.codehaus.groovy.ast.ClassCodeExpressionTransformer
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.expr.ClassExpression
import org.codehaus.groovy.ast.expr.ClosureExpression
import org.codehaus.groovy.ast.expr.ConstructorCallExpression
import org.codehaus.groovy.ast.expr.Expression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.PropertyExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.SourceUnit
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import org.codehaus.groovy.syntax.SyntaxException

/**
 * PathConfinementCustomizer -- the groovy sandbox's path half (FS 0.9.51, FS-GS).
 *
 * <p>Runs at CONVERSION, before any name is resolved, and rewrites the shapes Claude writes
 * naturally into the confined DSL helpers on {@link SecureMcpScript}:</p>
 * <ul>
 *   <li>{@code new File(a...)}            -> {@code file(a...)}</li>
 *   <li>{@code Paths.get(a...)}          -> {@code path(a...)}</li>
 *   <li>{@code Path.of(a...)}            -> {@code path(a...)}</li>
 * </ul>
 * <p>{@code file()} and {@code path()} resolve a relative name against {@code workingDir} and refuse
 * a canonical path outside the allowed directories, so the script keeps its shape and gains the
 * confinement. Constructors and factories that open a path directly and cannot be redirected
 * ({@code FileInputStream}, {@code FileWriter}, {@code RandomAccessFile}, {@code File.createTempFile},
 * {@code FileSystems.getDefault()} ...) are refused at compile time with a message that names the
 * helper to use instead.</p>
 *
 * <p>Why rewrite rather than ban: on 2026-09-22 the probe script that found the hole was
 * {@code new File('alpha.txt')} -- the ordinary thing to write. A sandbox that refuses the ordinary
 * thing is one Claude works around; a sandbox that quietly makes the ordinary thing safe is one it
 * never notices. Practice #3506 records the original behaviour.</p>
 *
 * <p>Closure bodies are walked explicitly: {@code ClosureExpression.transformExpression} returns
 * itself without visiting its code, so a {@code files.each { new File(it) }} would otherwise slip
 * through untouched.</p>
 */
@CompileStatic
class PathConfinementCustomizer extends CompilationCustomizer {

    static final Set<String> FILE_TYPES  = ['File', 'java.io.File'] as Set<String>
    static final Set<String> PATHS_TYPES = ['Paths', 'java.nio.file.Paths'] as Set<String>
    static final Set<String> PATH_TYPES  = ['Path', 'java.nio.file.Path'] as Set<String>

    /** Types whose constructor opens a path directly and cannot be confined by rewriting. */
    static final Set<String> REFUSED_CTORS = [
        'FileInputStream', 'FileOutputStream', 'FileReader', 'FileWriter', 'RandomAccessFile',
        'PrintWriter', 'PrintStream', 'java.io.FileInputStream', 'java.io.FileOutputStream',
        'java.io.FileReader', 'java.io.FileWriter', 'java.io.RandomAccessFile',
        'java.io.PrintWriter', 'java.io.PrintStream',
    ] as Set<String>

    /** Static factories that produce an unconfined File/Path/FileSystem. */
    static final Map<String, Set<String>> REFUSED_STATICS = [
        'File'                     : ['createTempFile', 'listRoots'] as Set<String>,
        'java.io.File'             : ['createTempFile', 'listRoots'] as Set<String>,
        'Files'                    : ['createTempFile', 'createTempDirectory'] as Set<String>,
        'java.nio.file.Files'      : ['createTempFile', 'createTempDirectory'] as Set<String>,
        'FileSystems'              : ['getDefault', 'newFileSystem', 'getFileSystem'] as Set<String>,
        'java.nio.file.FileSystems': ['getDefault', 'newFileSystem', 'getFileSystem'] as Set<String>,
    ]

    PathConfinementCustomizer() {
        super(CompilePhase.CONVERSION)
    }

    @Override
    void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        new Rewriter(source).visitClass(classNode)
    }

    @CompileStatic
    static class Rewriter extends ClassCodeExpressionTransformer {

        private final SourceUnit source

        Rewriter(SourceUnit source) { this.source = source }

        @Override
        protected SourceUnit getSourceUnit() { source }

        @Override
        Expression transform(Expression expr) {
            if (expr == null) return null

            if (expr instanceof ClosureExpression) {
                ClosureExpression ce = (ClosureExpression) expr
                ce.code?.visit(this)
                return ce
            }

            if (expr instanceof ConstructorCallExpression) {
                ConstructorCallExpression cce = (ConstructorCallExpression) expr
                String typeName = cce.type.name
                if (FILE_TYPES.contains(typeName)) {
                    return helperCall('file', transform(cce.arguments), expr)
                }
                if (REFUSED_CTORS.contains(typeName)) {
                    refuse(expr, "new ${typeName}(...) opens a path outside the sandbox. Use file('<name>') " +
                                 "(returns a confined java.io.File -- file(...).newOutputStream(), .withWriter{}, .text = ...) " +
                                 "or readText/writeText/appendText.")
                    return expr
                }
            }

            if (expr instanceof MethodCallExpression) {
                MethodCallExpression mce = (MethodCallExpression) expr
                String owner = staticOwnerName(mce.objectExpression)
                String method = mce.methodAsString
                if (owner != null && method != null) {
                    if (PATHS_TYPES.contains(owner) && method == 'get') {
                        return helperCall('path', transform(mce.arguments), expr)
                    }
                    if (PATH_TYPES.contains(owner) && method == 'of') {
                        return helperCall('path', transform(mce.arguments), expr)
                    }
                    Set<String> refused = REFUSED_STATICS.get(owner)
                    if (refused != null && refused.contains(method)) {
                        refuse(expr, "${owner}.${method}(...) produces an unconfined location. Use file('<name>') or " +
                                     "path('<name>') under workingDir instead.")
                        return expr
                    }
                }
            }

            return super.transform(expr)
        }

        /** The simple or qualified name a static call is made on, or null when it is not one. */
        private static String staticOwnerName(Expression obj) {
            if (obj instanceof VariableExpression) return ((VariableExpression) obj).name
            if (obj instanceof ClassExpression)    return ((ClassExpression) obj).type.name
            if (obj instanceof PropertyExpression) return ((PropertyExpression) obj).text   // java.nio.file.Paths
            return null
        }

        private static Expression helperCall(String helper, Expression args, Expression original) {
            MethodCallExpression call = new MethodCallExpression(VariableExpression.THIS_EXPRESSION, helper, args)
            call.implicitThis = true
            call.setSourcePosition(original)
            return call
        }

        private void refuse(Expression at, String message) {
            source.addError(new SyntaxException("SANDBOX: ${message}".toString(), at.lineNumber, at.columnNumber))
        }
    }
}
