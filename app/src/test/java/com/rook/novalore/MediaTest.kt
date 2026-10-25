package com.rook.novalore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media types, shows with seasons and episodes, filling in parts, and the year in review. */
class MediaTest {

    /** A show with two seasons of three episodes, and a movie. */
    private fun show(): StoreData {
        val d = StoreData()
        d.works.add(Work(id = "show", title = "Severance", author = "Apple TV+", format = "TV show", state = States.CURRENTLY))
        val plan = listOf(1, 2).map { s ->
            PlannedPart("Season $s", "Season", s, children = numberedParts("Episode", "Episode", 1, 3))
        }
        d.addParts("show", plan)
        d.works.add(Work(id = "movie", title = "Arrival", author = "Denis Villeneuve", format = "Movie", state = States.READ))
        return d
    }

    @Test fun everyFormatHasAGroupAndOldFormatsStayReading() {
        for (f in listOf("Short story", "Novelette", "Novella", "Novel", "Collection", "Anthology", "Other")) {
            assertEquals(MediaGroup.READ, Formats.groupOf(f))
        }
        assertEquals(MediaGroup.WATCH, Formats.groupOf("Anime series"))
        assertEquals(MediaGroup.PLAY, Formats.groupOf("Video game"))
        assertEquals(MediaGroup.READ, Formats.groupOf("Something imported")) // unknown formats fall back to Other
        assertTrue("TV show" in Formats.containers)
        assertTrue("Episode" in Formats.parts && "Chapter" in Formats.parts)
        assertFalse("Movie" in Formats.parts)
        assertEquals("Season", Formats.childOf("TV show"))
        assertEquals("Episode", Formats.childOf("Season"))
        assertEquals("Volume", Formats.childOf("Manga"))
    }

    @Test fun addPartsBuildsTheTreeWithTheParentsCreator() {
        val d = show()
        assertEquals(2 + 6, d.works.count { it.format == "Season" || it.format == "Episode" })
        val season1 = d.works.first { it.title == "Season 1" }
        assertEquals("show", season1.parentId)
        val eps = d.works.filter { it.parentId == season1.id }
        assertEquals(listOf(1, 2, 3), eps.mapNotNull { it.position }.sorted())
        assertTrue(eps.all { it.author == "Apple TV+" })
    }

    @Test fun fillingInTwiceOnlyAddsWhatIsNew() {
        val d = show()
        val more = listOf(1, 2, 3).map { s ->
            PlannedPart("Season $s", "Season", s, children = numberedParts("Episode", "Episode", 1, if (s == 1) 4 else 3))
        }
        val created = d.addParts("show", more)
        // Season 1 gains episode 4; season 3 and its three episodes are new.
        assertEquals(1 + 1 + 3, created.size)
        assertEquals(3, d.works.count { it.format == "Season" })
    }

    @Test fun progressAndNextUpCountEpisodesNotSeasons() {
        val d = show().freeze()
        val r = d.rollupFor("show")
        assertEquals(6, r.total)
        assertEquals(0, r.read)
        assertEquals("Episode 1", d.nextUp("show")?.title)
        assertEquals(0f, d.progressFraction(d.work("show")!!))
    }

    @Test fun finishingEverySeasonEpisodeFinishesTheSeasonButNotTheShow() {
        val d = show()
        val season1 = d.works.first { it.title == "Season 1" }
        val eps = d.works.filter { it.parentId == season1.id }.sortedBy { it.position }
        d.markDone(eps[0].id)
        d.markDone(eps[1].id)
        assertEquals(States.TO_READ, d.work(season1.id)!!.state)
        d.markDone(eps[2].id)
        assertEquals(States.READ, d.work(season1.id)!!.state)
        assertEquals(States.CURRENTLY, d.work("show")!!.state) // left for the user to finish and review
        d.freeze()
        assertEquals(3, d.rollupFor("show").read)
        assertEquals("Season 2", d.parentOf(d.nextUp("show")!!)!!.title)
    }

    @Test fun showRatingAverageUsesSeasonReviewsFirst() {
        val d = show()
        val seasons = d.works.filter { it.format == "Season" }
        d.logs.add(ReadingLog(workId = seasons[0].id, finishedDate = "2026-01-01", rating = 9.0))
        val ep = d.works.first { it.parentId == seasons[1].id }
        d.logs.add(ReadingLog(workId = ep.id, finishedDate = "2026-02-01", rating = 5.0))
        d.freeze()
        assertEquals(9.0, d.rollupFor("show").avgRating!!, 0.001) // a season was rated, so episodes are ignored
    }

    @Test fun percentProgressForGames() {
        val d = StoreData(mutableListOf(Work(id = "g", title = "Hollow Knight", format = "Video game", pagesRead = 40))).freeze()
        assertEquals(0.4f, d.progressFraction(d.work("g")!!)!!, 0.001f)
    }

    @Test fun numberedPartsAreNumberedAndEmptyWhenBackwards() {
        val p = numberedParts("Volume", "Volume", 3, 5)
        assertEquals(listOf("Volume 3", "Volume 4", "Volume 5"), p.map { it.title })
        assertTrue(numberedParts("Volume", "Volume", 5, 3).isEmpty())
        assertEquals(3, p.countAll())
    }

    @Test fun ratingAnEpisodeReviewsItAndTicksItOff() {
        val d = show()
        val ep = d.works.first { it.format == "Episode" && it.position == 1 }
        val id = d.rate(ep.id, 8.0, "Great opener", today = "2026-10-01")
        val log = d.logs.single { it.workId == ep.id }
        assertEquals(id, log.id)
        assertEquals(8.0, log.rating!!, 0.0)
        assertEquals("2026-10-01", log.finishedDate)
        assertEquals(States.READ, d.work(ep.id)!!.state)
        // Rating again changes the same review instead of adding another.
        d.rate(ep.id, 9.5, today = "2026-10-02")
        assertEquals(1, d.logs.count { it.workId == ep.id })
        assertEquals(9.5, d.logs.single { it.workId == ep.id }.rating!!, 0.0)
        assertEquals("Great opener", d.logs.single { it.workId == ep.id }.verdict)
    }

    @Test fun ratingAShowKeepsItInProgressAndNothingIsSavedForAnEmptyRating() {
        val d = show()
        d.rate("show", 7.0)
        assertEquals(States.CURRENTLY, d.work("show")!!.state)
        val ep = d.works.first { it.format == "Episode" }
        assertNull(d.rate(ep.id, null, ""))
        assertTrue(d.logs.none { it.workId == ep.id })
    }

    @Test fun ratingsAreSummarisedPerLevel() {
        val d = show()
        val seasons = d.works.filter { it.format == "Season" }
        d.rate(seasons[0].id, 9.0)
        val eps = d.works.filter { it.format == "Episode" }
        d.rate(eps[0].id, 8.0)
        d.rate(eps[1].id, 7.0)
        d.freeze()
        val levels = d.ratingsByLevel("show")
        assertEquals(listOf("Season", "Episode"), levels.map { it.format })
        assertEquals(1, levels[0].rated)
        assertEquals(2, levels[0].total)
        assertEquals(2, levels[1].rated)
        assertEquals(6, levels[1].total)
        assertEquals(7.5, levels[1].avg!!, 0.001)
    }

    @Test fun typedPartOfMatchesTheLibraryOrCreatesAParent() {
        val d = show()
        // An existing show, matched whatever the capitals.
        assertEquals("show", d.resolveParent("  severance ", "new", "Episode", "", States.TO_READ))
        // A story in an anthology that isn't in the library yet becomes a new collection, in progress.
        val story = Work(id = "story", title = "The Lottery", author = "Shirley Jackson", format = "Short story", state = States.READ)
        d.works.add(story)
        val id = d.resolveParent("The New Yorker Stories", "story", "Short story", "Shirley Jackson", States.READ)!!
        val made = d.work(id)!!
        assertEquals("Collection", made.format)
        assertEquals("Shirley Jackson", made.author)
        assertEquals(States.CURRENTLY, made.state)
        // A movie in a franchise becomes a movie series; blank means nothing.
        assertEquals("Movie series", d.work(d.resolveParent("Dune", "movie", "Movie", "", States.TO_READ)!!)!!.format)
        assertNull(d.resolveParent("   ", "movie", "Movie", "", States.TO_READ))
    }

    @Test fun typedPartOfNeverPointsInsideItself() {
        val d = show()
        val season1 = d.works.first { it.title == "Season 1" }
        // The show can't be "part of" its own season: a new parent is made instead of a loop.
        val id = d.resolveParent("Season 1", "show", "TV show", "", States.TO_READ)
        assertTrue(id != season1.id)
    }

    @Test fun coversOnlyCropWhenTheShapeNearlyMatches() {
        assertTrue(fillsFrame(0.67f, 0.68f))      // a book cover in a book-shaped tile
        assertFalse(fillsFrame(1.0f, 0.68f))      // a square game box in the same tile: shown whole
        assertFalse(fillsFrame(0.68f, 1.47f))     // a book cover on the wide detail header: shown whole
        assertFalse(fillsFrame(1.3f, 0f))
    }

    @Test fun coversSnapToTallSquareOrWide() {
        assertEquals(CoverShape.TALL, CoverShape.of(0.67f))   // book, poster, DVD/Blu-ray/game case
        assertEquals(CoverShape.TALL, CoverShape.of(0.75f))
        assertEquals(CoverShape.SQUARE, CoverShape.of(1.0f))  // CD-case game, album
        assertEquals(CoverShape.SQUARE, CoverShape.of(1.13f)) // PlayStation jewel case
        assertEquals(CoverShape.WIDE, CoverShape.of(1.43f))   // landscape game box
        assertEquals(CoverShape.WIDE, CoverShape.of(1.78f))   // 16:9 banner
        assertEquals(CoverShape.TALL, CoverShape.of(0f))
    }

    @Test fun initialsSkipSmallWordsAndKeepNumbers() {
        assertEquals("CO", initials("The Colour Out of Space"))
        assertEquals("S1", initials("Season 1"))
        assertEquals("A", initials("Arrival"))
        assertEquals("?", initials(""))
    }

    @Test fun wikipediaDescriptionsGiveTheCreator() {
        assertEquals("Christopher Nolan", wikiCreator("2010 film by Christopher Nolan"))
        assertEquals("Team Cherry", wikiCreator("2017 video game developed by Team Cherry"))
        assertNull(wikiCreator("2017 video game"))
    }

    @Test fun yearStatsCountByTypeMonthAndRevisits() {
        val d = show()
        d.logs.add(ReadingLog(workId = "movie", finishedDate = "2025-12-30", rating = 8.0))
        d.logs.add(ReadingLog(workId = "movie", finishedDate = "2026-03-04", rating = 9.5))
        d.logs.add(ReadingLog(workId = "show", finishedDate = "2026-03-20", rating = 7.0))
        d.works.add(Work(id = "book", title = "Dagon", author = "H. P. Lovecraft", format = "Short story"))
        d.logs.add(ReadingLog(workId = "book", finishedDate = "2026-07-01", rating = 6.0))
        d.logs.add(ReadingLog(workId = "book", finishedDate = "2026-08-01", dnf = true))
        d.freeze()

        assertEquals(listOf(2026, 2025), d.reviewYears())
        val s = d.yearStats(2026)
        assertEquals(3, s.finished)
        assertEquals(1, s.dropped)
        assertEquals(2, s.byGroup[MediaGroup.WATCH])
        assertEquals(1, s.byGroup[MediaGroup.READ])
        assertEquals(0, s.byGroup[MediaGroup.PLAY])
        assertEquals(2, s.months[2]) // March
        assertEquals(1, s.months[6]) // July
        assertEquals(2, s.rereviews) // Arrival seen in 2025, Dagon's second review
        assertEquals(listOf("Arrival", "Severance", "Dagon"), s.topRated.map { it.first.title })
        assertEquals((9.5 + 7.0 + 6.0) / 3, s.avgRating!!, 0.001)
    }
}
