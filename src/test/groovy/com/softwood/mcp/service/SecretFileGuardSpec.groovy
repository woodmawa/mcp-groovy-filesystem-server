package com.softwood.mcp.service

import groovy.json.JsonOutput
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title
import spock.lang.Unroll

import java.nio.file.Files
import java.nio.file.Path

/**
 * FS 0.9.120 -- the user-home {@code .gradle/gradle.properties} is never read, written, searched or scripted against.
 *
 * <p>It holds the signing and Sonatype credentials. On 2026-11-04 a chat checked one JDK line in it with
 * {@code Get-Content}; the whole file went into the FS job output, AW stored it, and the next chat's progress call
 * printed the password (rotated the same day). A project's own {@code gradle.properties} is NOT this file and stays
 * readable. Gradle itself still reads it -- the guard is on what FS hands back, not on the build.</p>
 *
 * <ul>
 *   <li>SF-1: SecretFiles recognises the file however the path is written; a project gradle.properties is not it</li>
 *   <li>SF-2 (RED): a script that names it is refused; a script in that directory that names it is refused</li>
 *   <li>SF-3 CONTROL: a project gradle.properties and an ordinary gradle call pass the script check</li>
 *   <li>SF-4 (RED): file_read refuses it and returns none of its content; the sibling project file reads</li>
 *   <li>SF-5 (RED): file_write refuses it and the file is unchanged</li>
 *   <li>SF-6 (RED): a content search over a tree that holds it never returns its lines; a sibling's match still comes back</li>
 *   <li>SF-7 (RED, X86 P3-6.1): exists on it answers allowed:false with the reason, never a bare exists:false; the
 *       project file still answers exists:true</li>
 * </ul>
 */
@groovy.transform.CompileDynamic
@SpringBootTest
@ActiveProfiles('test')
@Title('FS -- SF the user-home gradle.properties is never served')
class SecretFileGuardSpec extends Specification {

    static final String SECRET = 'ossrhPassword=sf-spec-not-a-real-password'

    @Autowired FileReadService fileReadService
    @Autowired FileWriteService fileWriteService
    @Autowired FileSearchService fileSearchService
    @Autowired SecurityService securityService

    @TempDir Path tempDir

    private static String p(Path x) { x.toString().replace('\\', '/') }

    private Path secretFile() {
        Path f = Files.createDirectories(tempDir.resolve('home').resolve('.gradle')).resolve('gradle.properties')
        f.text = "org.gradle.java.home=C:/jdk-25\n${SECRET}\n"
        return f
    }

    private Path projectFile() {
        Path f = Files.createDirectories(tempDir.resolve('home').resolve('proj')).resolve('gradle.properties')
        f.text = "version=1.0\nossrhPassword=project-placeholder\n"
        return f
    }

    @Unroll
    def 'SF-1: #path -> #expected'() {
        expect:
        SecretFiles.isSecret(path) == expected

        where:
        path                                                        || expected
        'C:/Users/willw/.gradle/gradle.properties'                  || true
        'C:\\Users\\willw\\.gradle\\gradle.properties'              || true
        '/home/will/.GRADLE/Gradle.Properties'                      || true
        'C:/Users/willw/IdeaProjects/mcp-x/gradle.properties'       || false
        'C:/Users/willw/.gradle/init.gradle'                        || false
        null                                                        || false
    }

    @Unroll
    def 'SF-2 (RED): script is refused -- #script'() {
        when:
        securityService.validateScript(script, workingDir, 'powershell')

        then:
        SecurityException e = thrown()
        e.message.contains('gradle.properties')

        where:
        script                                                          | workingDir
        'Get-Content "$env:USERPROFILE\\.gradle\\gradle.properties"'    | 'C:/Users/willw/IdeaProjects'
        'cat ~/.gradle/gradle.properties'                               | 'C:/Users/willw/IdeaProjects'
        'Select-String -Path C:/Users/willw/.gradle/gradle.properties -Pattern java' | 'C:/Users/willw/IdeaProjects'
        'Get-Content gradle.properties'                                 | 'C:/Users/willw/.gradle'
    }

    @Unroll
    def 'SF-3 CONTROL: script passes -- #script'() {
        when:
        securityService.validateScript(script, 'C:/Users/willw/IdeaProjects/mcp-x', 'powershell')

        then:
        noExceptionThrown()

        where:
        script << ['Get-Content gradle.properties', '.\\gradlew.bat test --continue']
    }

    def 'SF-4 (RED): file_read refuses the secret file and returns none of it; the project file reads'() {
        given:
        Path secret = secretFile()
        Path project = projectFile()

        when:
        String refused = JsonOutput.toJson(fileReadService.handleToolCall('file_read',
            [action: 'read', path: p(secret), options: [:]], 1))
        String served = JsonOutput.toJson(fileReadService.handleToolCall('file_read',
            [action: 'read', path: p(project), options: [:]], 2))

        then:
        !refused.contains('sf-spec-not-a-real-password')
        refused.toLowerCase().contains('not allowed')
        served.contains('project-placeholder')
    }

    def 'SF-5 (RED): file_write refuses the secret file and leaves it unchanged'() {
        given:
        Path secret = secretFile()
        String before = secret.text

        when:
        fileWriteService.handleToolCall('file_write',
            [action: 'write', path: p(secret), content: 'clobbered'], 1)

        then:
        secret.text == before
    }

    def 'SF-6 (RED): a content search never returns the secret file; a sibling match still comes back'() {
        given:
        secretFile()
        projectFile()

        when:
        String out = JsonOutput.toJson(fileSearchService.handleToolCall('file_search',
            [action: 'content', path: p(tempDir.resolve('home')),
             options: [contentPattern: 'ossrhPassword', filePattern: '.*']], 1))

        then:
        out.contains('project-placeholder')
        !out.contains('sf-spec-not-a-real-password')
    }

    def 'SF-7 (RED): exists on the secret file says not allowed and why; the project file still exists'() {
        given:
        Path secret = secretFile()
        Path project = projectFile()

        when: 'the tool payload is the text of the first content block'
        Map refused = payload(fileReadService.handleToolCall('file_read', [action: 'exists', path: p(secret)], 1))
        Map served  = payload(fileReadService.handleToolCall('file_read', [action: 'exists', path: p(project)], 2))

        then: 'the secret file is reported as refused, with the reason -- not as absent'
        refused.allowed == false
        (refused.reason as String).contains('signing and Sonatype credentials')
        !refused.containsKey('exists')
        !JsonOutput.toJson(refused).contains('sf-spec-not-a-real-password')

        and: 'the project gradle.properties is unaffected'
        served.exists == true
        !served.containsKey('allowed')
    }

    private static Map payload(Object response) {
        Map outer = new groovy.json.JsonSlurper().parseText(JsonOutput.toJson(response)) as Map
        String text = (((outer.result as Map).content as List)[0] as Map).text as String
        return new groovy.json.JsonSlurper().parseText(text) as Map
    }
}
