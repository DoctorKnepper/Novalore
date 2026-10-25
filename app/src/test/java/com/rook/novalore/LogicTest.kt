package com.rook.novalore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LogicTest {

    private fun sample(): StoreData {
        val coll = Work(id = "c", title = "Dagon and Other Tales", author = "H. P. Lovecraft", format = "Collection", state = States.CURRENTLY)
        val a = Work(id = "a", title = "Dagon", author = "H. P. Lovecraft", state = States.READ, parentId = "c", position = 2)
        val b = Work(id = "b", title = "Polaris", author = "H. P. Lovecraft", state = States.TO_READ, parentId = "c", position = 1)
        val d = StoreData(mutableListOf(coll, a, b), mutableListOf(
            ReadingLog(workId = "a", finishedDate = "2026-10-07", verdict = "Good, with \"quotes\", commas", review = "Line 1\nLine 2", rating = 5.0),
            ReadingLog(workId = "a", finishedDate = "2026-10-20", rating = 7.5)
        ))
        return d.freeze()
    }

    // ---- safety ----

    @Test fun coverPathsRejectTraversal() {
        assertTrue(isSafeCoverPath("covers/abc.jpg"))
        assertFalse(isSafeCoverPath("covers/../data.json"))
        assertFalse(isSafeCoverPath("../data.json"))
        assertFalse(isSafeCoverPath("/etc/passwd"))
        assertFalse(isSafeCoverPath("covers/sub/x.jpg"))
        assertFalse(isSafeCoverPath("covers/.."))
        assertFalse(isSafeCoverPath("covers\\..\\x"))
        assertFalse(isSafeCoverPath(null))
    }

    @Test fun resolveCoverStaysInside() {
        val root = Files.createTempDirectory("sb").toFile()
        File(root, "covers").mkdirs()
        assertNotNull(resolveCover(root, "covers/x.jpg"))
        assertNull(resolveCover(root, "covers/../x.jpg"))
        root.deleteRecursively()
    }

    @Test fun copyLimitedThrowsWhenTooLarge() {
        val big = java.io.ByteArrayInputStream(ByteArray(1000))
        var threw = false
        try { copyLimited(big, java.io.ByteArrayOutputStream(), 999) } catch (_: java.io.IOException) { threw = true }
        assertTrue(threw)
    }

    // ---- model ----

    @Test fun indexesMatchLinearLookups() {
        val d = sample()
        assertEquals("a", d.work("a")?.id)
        assertEquals("2026-10-20", d.latestLog("a")?.finishedDate)
        assertEquals(listOf("b", "a"), d.childrenOf("c").map { it.id }) // ordered by position
        val copy = d.deepCopy() // un-frozen copy uses linear lookups
        assertEquals(d.childrenOf("c").map { it.id }, copy.childrenOf("c").map { it.id })
    }

    @Test fun rollupAndProgress() {
        val d = sample()
        val r = d.rollupFor("c")
        assertEquals(2, r.total); assertEquals(1, r.read); assertEquals(1, r.rated)
        assertEquals(7.5, r.avgRating!!, 0.0001)
        assertEquals(0.5f, d.progressFraction(d.work("c")!!)!!, 0.0001f)
        val book = Work(pageCount = 200, pagesRead = 50)
        assertEquals(0.25f, StoreData(mutableListOf(book)).freeze().progressFraction(book)!!, 0.0001f)
    }

    @Test fun parentOptionsPreventLoops() {
        val d = sample()
        assertTrue(d.parentOptions("a").any { it.id == "c" })
        assertFalse(d.parentOptions("c").any { it.id == "c" })
        val nested = d.deepCopy().apply { works.add(Work(id = "x", format = "Anthology", parentId = "a")) }.freeze()
        // "a" contains "x", so "x" may not become a's parent's parent... and a can't go inside x
        assertFalse(nested.parentOptions("a").any { it.id == "x" })
    }

    @Test fun sanitizeBreaksLoopsAndBadPaths() {
        val d = StoreData(mutableListOf(
            Work(id = "1", parentId = "2", imagePath = "covers/../evil"),
            Work(id = "2", parentId = "1"),
            Work(id = "3", parentId = "missing", state = "weird")
        )).sanitize().freeze()
        assertNull(d.work("1")!!.imagePath)
        assertTrue(d.work("1")!!.parentId == null || d.work("2")!!.parentId == null)
        assertNull(d.work("3")!!.parentId)
        assertEquals(States.TO_READ, d.work("3")!!.state)
    }

    @Test fun sanitizeDropsDuplicateIds() {
        val d = StoreData(
            mutableListOf(Work(id = "1", title = "first"), Work(id = "1", title = "second")),
            mutableListOf(ReadingLog(id = "l", workId = "1"), ReadingLog(id = "l", workId = "1"))
        ).sanitize()
        assertEquals(1, d.works.size)
        assertEquals("first", d.works[0].title)
        assertEquals(1, d.logs.size)
    }

    @Test fun removeAndRestore() {
        val d = sample().deepCopy()
        val removed = d.removeWork("c")!!
        assertNull(d.works.firstOrNull { it.id == "c" })
        assertNull(d.works.first { it.id == "a" }.parentId)
        d.restore(removed)
        assertEquals("c", d.works.first { it.id == "a" }.parentId)
        assertEquals(0, d.works.indexOfFirst { it.id == "c" })
    }

    @Test fun setStateRecordsStartDate() {
        val d = sample().deepCopy()
        d.setState("b", States.CURRENTLY, "2026-10-23")
        assertEquals("2026-10-23", d.work("b")!!.startedDate)
    }

    @Test fun searchCoversReviewsAndParent() {
        val d = sample()
        assertTrue(d.matches(d.work("a")!!, "line 2"))
        assertTrue(d.matches(d.work("b")!!, "other tales"))
        assertFalse(d.matches(d.work("b")!!, "zzz"))
    }

    @Test fun reviewTemplateAppends() {
        assertEquals("What worked: \n\nWhat didn't: \n\nWho would enjoy it:", ReviewTemplates.apply("", ReviewTemplates.prosCons))
        assertTrue(ReviewTemplates.apply("Hi", ReviewTemplates.storyNotes).startsWith("Hi\n\nWhat happened:"))
    }

    // ---- CSV ----

    @Test fun csvParserHandlesQuotesAndNewlines() {
        val rows = Csv.parse("﻿a,b,c\r\n\"x, y\",\"he said \"\"hi\"\"\",\"multi\nline\"\n\n1,,3")
        assertEquals(listOf("a", "b", "c"), rows[0])
        assertEquals(listOf("x, y", "he said \"hi\"", "multi\nline"), rows[1])
        assertEquals(listOf("1", "", "3"), rows[2])
        assertEquals(3, rows.size)
    }

    @Test fun csvRoundTrip() {
        val d = sample()
        val text = Csv.build(d)
        val parsed = Csv.parseImport(text)!!
        val fresh = StoreData()
        val counts = Csv.applyImport(fresh, parsed)
        assertEquals(3, counts.newWorks)
        assertEquals(2, counts.newLogs)
        assertEquals(2, counts.linked)
        val f = fresh.freeze()
        val dagon = f.works.first { it.title == "Dagon" }
        assertEquals("Dagon and Other Tales", f.parentOf(dagon)?.title)
        assertEquals(2, dagon.position)
        val log = f.logsFor(dagon.id).last()
        assertEquals("Good, with \"quotes\", commas", log.verdict)
        assertEquals("Line 1\nLine 2", log.review)
        assertEquals(5.0, log.rating!!, 0.0)
        assertEquals(States.CURRENTLY, f.works.first { it.title == "Dagon and Other Tales" }.state)
    }

    @Test fun reimportDoesNotDuplicate() {
        val d = sample().deepCopy()
        val counts = Csv.applyImport(d, Csv.parseImport(Csv.build(sample()))!!)
        assertEquals(0, counts.newWorks)
        assertEquals(0, counts.newLogs)
        assertEquals(3, counts.matchedWorks)
        assertEquals(3, d.works.size)
    }

    @Test fun goodreadsStyleImport() {
        val csv = "Book Id,Title,Author,My Rating,Number of Pages,Year Published,Date Read,Exclusive Shelf,My Review\n" +
            "1,The Hobbit,J.R.R. Tolkien,4,310,1937,2024/05/12,read,Lovely\n" +
            "2,Dune,Frank Herbert,0,600,1965,,to-read,\n" +
            "3,,Nobody,3,,,,read,\n"
        val p = Csv.parseImport(csv)!!
        assertEquals(1, p.ignoredRows)
        val d = StoreData()
        Csv.applyImport(d, p)
        val hobbit = d.works.first { it.title == "The Hobbit" }
        assertEquals(States.READ, hobbit.state)
        assertEquals(310, hobbit.pageCount)
        assertEquals(1937, hobbit.year)
        assertEquals(8.0, d.logs.first { it.workId == hobbit.id }.rating!!, 0.0)
        assertEquals("2024-05-12", d.logs.first().finishedDate)
        val dune = d.works.first { it.title == "Dune" }
        assertEquals(States.TO_READ, dune.state)
        assertTrue(d.logs.none { it.workId == dune.id })
    }

    @Test fun nonReadingListIsRejected() {
        assertNull(Csv.parseImport("name,amount\nrent,100\n"))
    }

    @Test fun parsersAreForgiving() {
        assertEquals(7.5, Csv.parseRating("7,5", 1.0)!!, 0.0)
        assertEquals(7.5, Csv.parseRating("3.75", 2.0)!!, 0.0)
        assertNull(Csv.parseRating("0", 2.0))
        assertEquals("2026-10-06", Csv.parseDate("Oct 6, 2026"))
        assertEquals("", Csv.parseDate("someday"))
        assertEquals(States.DNF, Csv.parseState("DID_NOT_FINISH"))
        assertEquals(States.CURRENTLY, Csv.parseState("currently-reading"))
    }
}
