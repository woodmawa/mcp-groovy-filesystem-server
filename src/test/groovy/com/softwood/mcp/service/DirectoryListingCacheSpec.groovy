package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Title

import java.nio.file.Files
import java.nio.file.Path

/**
 * FS 0.9.54 DC -- the directory-listing cache is in-memory only, and still works.
 *
 * Until 0.9.54 a miss fell through to CS: read the whole mcp-servers practice list and scan it for a
 * 'directory-listing' row. Measured 2026-09-22: zero such rows had ever landed (the writer was refused by
 * CS's valence rule, silently), so the lookup could never hit -- and each one ledgered 20 practice 'shows'
 * that nobody saw (285 in 30 days, 0 judged). Nothing had ever specified this cache at all, which is how
 * a lookup that could not hit ran for months. These cases pin the half that remains.
 */
@Title('ContextServerClient -- DC the in-memory directory cache')
class DirectoryListingCacheSpec extends Specification {

    @TempDir Path dir

    ContextServerClient client() {
        ContextServerClient c = new ContextServerClient()
        c.directoryCacheEnabled = true
        return c
    }

    def 'DC-1: a persisted listing is served from memory while the directory is unchanged'() {
        given:
        ContextServerClient c = client()
        String p = dir.toString().replace('\\', '/')
        long mtime = new File(p).lastModified()

        when:
        c.persistDirectoryListingAsync(p, [[name: 'a.txt', type: 'file', size: 1] as Map<String, Object>], 'h1', mtime)
        def hit = c.getDirectoryListing(p)

        then:
        hit != null
        hit.listingHash == 'h1'
        hit.entries*.name == ['a.txt']
    }

    def 'DC-2: a change to the directory invalidates the entry'() {
        given:
        ContextServerClient c = client()
        String p = dir.toString().replace('\\', '/')
        c.persistDirectoryListingAsync(p, [], 'h1', new File(p).lastModified() - 5000)

        expect: 'the stored mtime no longer matches, so the caller lists the filesystem'
        c.getDirectoryListing(p) == null
    }

    def 'DC-3: a miss returns null -- there is no server lookup behind it any more'() {
        given:
        ContextServerClient c = client()
        Path sub = Files.createDirectories(dir.resolve('never-listed'))

        when:
        long t0 = System.nanoTime()
        def miss = c.getDirectoryListing(sub.toString().replace('\\', '/'))
        long ms = (System.nanoTime() - t0).intdiv(1_000_000)

        then:
        miss == null
        ms < 200
    }

    def 'DC-4: disabled means disabled'() {
        given:
        ContextServerClient c = new ContextServerClient()
        c.directoryCacheEnabled = false
        String p = dir.toString().replace('\\', '/')
        c.persistDirectoryListingAsync(p, [], 'h1', new File(p).lastModified())

        expect:
        c.getDirectoryListing(p) == null
    }
}
