package com.softwood.mcp.support

import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.extension.IMethodInterceptor
import org.spockframework.runtime.extension.IMethodInvocation
import org.spockframework.runtime.model.SpecInfo

/**
 * WS10-C (decision 377): installs {@link LivePortTripwire} once per test JVM and fails any feature during which a
 * live companion was reached. Registered in META-INF/services/org.spockframework.runtime.extension.IGlobalExtension.
 * The refusal itself is often swallowed by fail-open client code, so the feature is failed on the recorded hit,
 * not on the exception.
 */
class LivePortTripwireExtension implements IGlobalExtension {

    @Override
    void start() {
        LivePortTripwire.install()
    }

    @Override
    void visitSpec(SpecInfo spec) {
        spec.allFeatures.each { feature ->
            feature.addIterationInterceptor({ IMethodInvocation invocation ->
                int before = LivePortTripwire.HITS.size()
                invocation.proceed()
                List<String> after = new ArrayList<String>(LivePortTripwire.HITS)
                if (after.size() > before) {
                    throw new AssertionError("LIVE_PORT_TRIPWIRE: '${invocation.iteration?.displayName}' reached a live " +
                            "companion: ${after.subList(before, after.size())} -- use TestPorts.deadUrl() or a stub on a " +
                            "dynamic port (or -Dtests.allowLive=true for a deliberate live run)")
                }
            } as IMethodInterceptor)
        }
    }
}
