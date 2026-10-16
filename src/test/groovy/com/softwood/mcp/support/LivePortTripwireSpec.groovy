package com.softwood.mcp.support

import spock.lang.Specification
import spock.lang.Title

/**
 * WS10-C (decision 377). The tripwire is only worth having if it is really installed for every spec and really sees a
 * real client. Each feature makes a real HttpURLConnection; LPT-1 clears the hit it causes so the extension does not
 * fail it for doing its job.
 */
@Title('LivePortTripwire -- LPT-1..3 tests never reach a live companion')
class LivePortTripwireSpec extends Specification {

    def 'LPT-1: a real HttpURLConnection to the live CS port is refused by the tripwire and recorded'() {
        given:
        int before = LivePortTripwire.HITS.size()

        when:
        HttpURLConnection c = (HttpURLConnection) new URL('http://127.0.0.1:8082/mcp').openConnection()
        c.connectTimeout = 300
        c.connect()

        then:
        Exception e = thrown()
        (e.message ?: '').contains('LIVE_PORT_TRIPWIRE') || (e.cause?.message ?: '').contains('LIVE_PORT_TRIPWIRE')
        LivePortTripwire.HITS.size() == before + 1
        LivePortTripwire.HITS.last() == 'http://127.0.0.1:8082/mcp'

        cleanup: 'this feature caused the hit on purpose'
        while (LivePortTripwire.HITS.size() > before) { LivePortTripwire.HITS.remove(LivePortTripwire.HITS.size() - 1) }
    }

    def 'LPT-2: a dynamic dead port is not the tripwire\'s business -- refused by the OS, nothing recorded'() {
        given:
        int before = LivePortTripwire.HITS.size()
        String url = TestPorts.deadUrl()

        when:
        HttpURLConnection c = (HttpURLConnection) new URL(url + '/mcp').openConnection()
        c.connectTimeout = 300
        c.connect()

        then:
        thrown(ConnectException)
        LivePortTripwire.HITS.size() == before
        !LivePortTripwire.LIVE_PORTS.contains(new URI(url).port)
    }

    def 'LPT-3: the tripwire is the JVM default selector -- the global extension installed it before any spec ran'() {
        expect:
        ProxySelector.getDefault() instanceof LivePortTripwire
    }
}
