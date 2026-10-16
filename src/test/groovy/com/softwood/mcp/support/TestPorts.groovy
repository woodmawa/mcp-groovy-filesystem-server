package com.softwood.mcp.support

import groovy.transform.CompileStatic

/**
 * WS10-C (decision 377): ports for specs. Never a live companion port (8081-8084); see {@link LivePortTripwire}.
 */
@CompileStatic
final class TestPorts {

    private TestPorts() {}

    /** A port that was free a moment ago and is closed now: a connection to it is refused at once. */
    static int deadPort() {
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName('127.0.0.1'))
        try {
            return s.localPort
        } finally {
            s.close()
        }
    }

    /** http://127.0.0.1:&lt;deadPort&gt; -- for a client that must fail fast, fail-open, and touch nothing live. */
    static String deadUrl() {
        "http://127.0.0.1:${deadPort()}".toString()
    }
}
