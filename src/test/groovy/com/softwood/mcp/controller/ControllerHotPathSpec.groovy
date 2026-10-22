package com.softwood.mcp.controller

import spock.lang.Specification
import spock.lang.Title

/**
 * FS 0.9.116 -- productive-learning round 1b, 3b' (practice #260 made a check). The same spec lives in CS and AW.
 *
 * <p>#260 -- "McpController hot path must have ZERO blocking I/O" (the 2026-04-04 FS outage: a JDBC open on the
 * stdio response path timed every response out) -- earned 3 `y` acks in 12 PLAN-GATE shows. A rule a source scan can
 * answer belongs in a test. Every request and response passes through McpController, so the controller source may
 * name no blocking call: no file I/O, no JDBC, no HTTP client, no sleep, no blocking wait on a future. Work that needs
 * them goes to a service, off the path, or to an async submit.</p>
 *
 * HP-1  McpController names no blocking call
 * HP-2  the scanner catches each blocking form, and passes ThreadLocal/AtomicReference get()
 */
@Title("3b' -- #260 as a check: McpController names no blocking call")
class ControllerHotPathSpec extends Specification {

    static final File CONTROLLER = new File('src/main/groovy/com/softwood/mcp/controller/McpController.groovy')

    static final Map<String, String> BLOCKING = [
        'Files.'                  : /\bFiles\.[a-z]/,
        'new File('               : /\bnew\s+File\s*\(/,
        'JDBC connection'         : /\bDriverManager\b|\.getConnection\s*\(/,
        'JDBC statement'          : /\.execute(Query|Update)?\s*\(\s*["']/,
        'HTTP client'             : /\bHttpClient\b|\bHttpURLConnection\b|\.openConnection\s*\(/,
        'sleep'                   : /\bThread\.sleep\s*\(/,
        'timed future wait'       : /\.get\s*\(\s*\d+\s*,\s*TimeUnit/,
        'future join'             : /\.join\s*\(\s*\)/
    ]

    static List<String> blockingCalls(String src) {
        List<String> found = []
        src.readLines().eachWithIndex { String line, int i ->
            String code = line.replaceAll(/\/\/.*$/, '')
            if (code.trim().startsWith('*')) return
            BLOCKING.each { String name, String rx -> if (code =~ rx) found << ("${i + 1}: ${name}: ${line.trim()}" as String) }
        }
        return found
    }

    def 'HP-1: McpController names no blocking call'() {
        given:
        assert CONTROLLER.exists() : "run from the project dir: ${CONTROLLER.absolutePath}"

        expect:
        blockingCalls(CONTROLLER.getText('UTF-8')) == []
    }

    def 'HP-2: the scanner catches each blocking form and passes in-memory reads'() {
        given:
        String bad = '''
            byte[] b = Files.readAllBytes(p)
            def f = new File('x')
            Connection c = DriverManager.getConnection(url)
            stmt.executeQuery("SELECT 1")
            HttpClient.newHttpClient()
            Thread.sleep(10)
            future.get(5, TimeUnit.SECONDS)
            future.join()
        '''
        String ok = '''
            String s = CALLER_SESSION.get()
            Map m = predecessorRef.get()
            // Files.readAllBytes is mentioned in a comment only
             * javadoc: new File('x')
            String joined = parts.join(', ')
        '''

        expect:
        blockingCalls(bad).size() == 8
        blockingCalls(ok) == []
    }
}
