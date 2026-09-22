package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.Title

/**
 * FS-M3-HASH -- args_hash identifies the call, not the gate conversation around it.
 *
 * M3-advice-changed-the-next-call scores a PLAN-GATE refusal as 'followed' when the next call from
 * the same process differs in action or args_hash. Every acked retry adds options.planAck (and often
 * options.intent), so before FS 0.9.53 it always hashed differently and M3 would have read ~100% for
 * a caller who acknowledged every practice and changed nothing. Found 2026-09-22 on the first
 * plan_gate_fired rows the platform ever wrote.
 */
@Title('FilesystemTelemetryService -- FS-M3-HASH gate-only keys do not change args_hash')
class ArgsHashGateKeysSpec extends Specification {

    static Map<String, Object> call(Map<String, Object> options) {
        [action: 'write', path: 'C:/repo/src/Foo.groovy', content: 'class Foo {}',
         options: options] as Map<String, Object>
    }

    def 'FS-M3-HASH-1: an acked retry of an unchanged call hashes the same as the refused call'() {
        expect:
        FilesystemTelemetryService.buildArgsHash(call([verbose: true])) ==
            FilesystemTelemetryService.buildArgsHash(call([verbose: true, planAck: '7:y,9:n']))
    }

    def 'FS-M3-HASH-2: so does one that only adds or changes its intent'() {
        expect:
        FilesystemTelemetryService.buildArgsHash(call([intent: 'first wording'])) ==
            FilesystemTelemetryService.buildArgsHash(call([intent: 'second wording', planAck: '7:y']))
    }

    def 'FS-M3-HASH-3 (control): a retry whose WORK changed hashes differently'() {
        given:
        Map<String, Object> changed = call([planAck: '7:y'])
        changed.content = 'class Foo { int x }'

        expect: 'the whole point -- advice that changed the plan must still be visible'
        FilesystemTelemetryService.buildArgsHash(call([planAck: '7:y'])) !=
            FilesystemTelemetryService.buildArgsHash(changed)
    }

    def 'FS-M3-HASH-4 (control): a real option change still counts'() {
        expect:
        FilesystemTelemetryService.buildArgsHash(call([raw: true, planAck: '7:y'])) !=
            FilesystemTelemetryService.buildArgsHash(call([raw: false, planAck: '7:y']))
    }

    def 'FS-M3-HASH-5: key order never changes the hash'() {
        expect:
        FilesystemTelemetryService.buildArgsHash([b: 2, a: 1, options: [y: 1, x: 2]] as Map<String, Object>) ==
            FilesystemTelemetryService.buildArgsHash([a: 1, b: 2, options: [x: 2, y: 1]] as Map<String, Object>)
    }
}
