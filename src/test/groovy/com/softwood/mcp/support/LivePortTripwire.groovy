package com.softwood.mcp.support

import groovy.transform.CompileStatic

/**
 * WS10-C (decision 377, FS 0.9.101): a test run must never reach a LIVE companion. Installed as the JVM's default
 * {@link ProxySelector} by {@link LivePortTripwireExtension} before any spec runs. HttpURLConnection, OkHttp and
 * java.net.http ask the default selector before every connection, so a connection to localhost on a live companion
 * port is refused here and recorded; the extension then fails the feature that made it, naming the URL.
 *
 * <p>Why a tripwire and not a grep: the leaks that polluted live data (FS 0.9.61: 20,247 temp paths in the live
 * file_hash_registry; FS 0.9.97: fsjob rows in the live AW db) and the hand-built clients found on 2026-10-16 were all
 * literals nobody had looked at. Specs use {@link TestPorts} for a dynamic port instead.</p>
 *
 * <p>Deliberate live runs (an E2E spec) pass {@code -Dtests.allowLive=true}.</p>
 */
@CompileStatic
class LivePortTripwire extends ProxySelector {

    /** The companions in claude-sync/mcp-http-servers.json: FS 8081/8083, CS 8082, AW 8084. */
    static final Set<Integer> LIVE_PORTS = [8081, 8082, 8083, 8084] as Set<Integer>
    static final Set<String> LOCAL_HOSTS = ['localhost', '127.0.0.1', '::1', '[::1]', '0.0.0.0'] as Set<String>

    /** Every refused target, in order. The extension reads it per feature; LivePortTripwireSpec clears its own. */
    static final List<String> HITS = Collections.synchronizedList(new ArrayList<String>())

    final ProxySelector delegate

    LivePortTripwire(ProxySelector delegate) { this.delegate = delegate }

    static boolean allowLive() { Boolean.getBoolean('tests.allowLive') }

    static boolean isLive(URI uri) {
        uri != null && uri.host != null && LOCAL_HOSTS.contains(uri.host.toLowerCase()) && LIVE_PORTS.contains(uri.port)
    }

    @Override
    List<Proxy> select(URI uri) {
        if (!allowLive() && isLive(uri)) {
            HITS.add(uri.toString())
            throw new IllegalArgumentException("LIVE_PORT_TRIPWIRE: a test tried to reach a live companion: ${uri}" as String)
        }
        delegate != null ? delegate.select(uri) : [Proxy.NO_PROXY]
    }

    @Override
    void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        delegate?.connectFailed(uri, sa, ioe)
    }

    /** Idempotent: installs once per JVM, wrapping whatever selector was there. */
    static synchronized void install() {
        if (!(ProxySelector.getDefault() instanceof LivePortTripwire)) {
            ProxySelector.setDefault(new LivePortTripwire(ProxySelector.getDefault()))
        }
    }
}
