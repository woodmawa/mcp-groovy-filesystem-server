package com.softwood.mcp.service

import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/**
 * FS 0.9.62 -- the dead CS persistence half of the structure / directory-listing cache is removed.
 *
 * <p>FS 0.9.54 made {@code persistStructureAsync} a no-op and dropped the network half of the directory
 * cache, but left the no-op, its caller, the unused {@code structureGroupId} setting, and comments saying
 * "ZERO rows of either category exist". They did: 160 rows sat in CS {@code project_group_practices}
 * (counted in {@code best_practices}, the wrong table) until deleted on 2026-09-24. The in-memory listing
 * cache is live and useful, so it stays -- renamed from {@code persistDirectoryListingAsync}, which
 * persisted nothing, to {@code cacheDirectoryListing}.</p>
 *
 * <p>Runtime assertions on the class, not a scan of its source (practice 3256).</p>
 */
class DeadCachePersistenceRemovedSpec extends Specification {

    @TempDir Path tmp

    def 'DCR-1: no structure-persistence entry point or group setting remains'() {
        given:
        ContextServerClient c = new ContextServerClient()

        expect:
        !ContextServerClient.metaClass.respondsTo(c, 'persistStructureAsync')
        !ContextServerClient.metaClass.respondsTo(c, 'persistDirectoryListingAsync')
        !c.hasProperty('structureGroupId')
    }

    def 'DCR-2: the in-memory directory cache still hits, and invalidates when the directory changes'() {
        given:
        ContextServerClient c = new ContextServerClient(directoryCacheEnabled: true)
        String dir = tmp.toString()
        long mtime = new File(dir).lastModified()

        when:
        c.cacheDirectoryListing(dir, [[name: 'a.txt']], 'h1', mtime)

        then: 'a same-session repeat is served from memory'
        c.getDirectoryListing(dir)?.listingHash == 'h1'

        when: 'the directory changes'
        new File(dir, 'b.txt').text = 'x'
        new File(dir).setLastModified(mtime + 5000)

        then: 'the stale entry is dropped, not served'
        c.getDirectoryListing(dir) == null
    }
}
