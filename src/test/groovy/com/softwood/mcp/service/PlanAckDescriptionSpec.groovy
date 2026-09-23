package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.Unroll

/**
 * FS 0.9.56 -- the planAck description every gated tool shows names all three verdicts.
 *
 * <p>CS 1.1.17 added {@code c} (applies, my call already complies) beside {@code y} (applies, I am
 * changing the call) and {@code n} (not about this task). CS's refusal text teaches it at the moment
 * of refusal, but the schema description is read first and read every time; if it still says
 * {@code y|n} the two surfaces contradict each other and the old vocabulary wins by repetition.</p>
 *
 * <p>Before 0.9.56 the description was three hand-copied strings, and ToolsService's had already
 * drifted from the other two. It is now one constant, {@code PlanGateGuard.PLAN_ACK_DESCRIPTION}.
 * Asserted on the runtime tool definitions -- what the model is actually shown -- in positive form.</p>
 */
@groovy.transform.CompileDynamic
class PlanAckDescriptionSpec extends Specification {

    /** Depth-first search for the planAck property schema anywhere in a tool definition. */
    private static Map findPlanAck(Object node) {
        if (node instanceof Map) {
            Map m = node as Map
            if (m.containsKey('planAck') && m.get('planAck') instanceof Map) return m.get('planAck') as Map
            for (Object v : m.values()) {
                Map hit = findPlanAck(v)
                if (hit != null) return hit
            }
        } else if (node instanceof Collection) {
            for (Object v : (node as Collection)) {
                Map hit = findPlanAck(v)
                if (hit != null) return hit
            }
        }
        return null
    }

    @Unroll
    def "PAD-1: #name's planAck description names y, c and n"() {
        when:
        Map planAck = findPlanAck(handler.getToolDefinitions())

        then: 'the property exists -- a check that found nothing measured nothing'
        planAck != null

        and:
        String d = planAck.get('description') as String
        d.contains('<id>:y|c|n')
        d.contains('c = applies and my call already complies')
        d.contains('y = applies and I am changing the call')

        where:
        name               | handler
        'ExecuteService'   | new ExecuteService()
        'FileWriteService' | new FileWriteService()
        'ToolsService'     | new ToolsService()
    }

    def "PAD-2: the three tools show one description, not three copies"() {
        expect:
        [new ExecuteService(), new FileWriteService(), new ToolsService()]
            .collect { findPlanAck(it.getToolDefinitions())?.get('description') }
            .unique().size() == 1
    }
}
