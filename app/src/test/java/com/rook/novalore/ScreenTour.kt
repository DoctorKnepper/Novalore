package com.rook.novalore

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real app with a mixed library (stories, a show with seasons, a movie, a game, a manga) and walks
 * through the main screens. It fails if any screen crashes, and saves a screenshot of each to build/screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class ScreenTour {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File("build/screens").apply { mkdirs() }

    /** Draws every window (the activity plus any dialogs or menus) into one picture, the way the screen shows them. */
    private fun shot(name: String, idle: Boolean = true) {
        if (idle) rule.waitForIdle()
        val wmg = Class.forName("android.view.WindowManagerGlobal")
        val global = wmg.getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = wmg.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<View>
        val base = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        for (v in views.filter { it.isShown && it.width > 0 }) {
            val loc = IntArray(2).also { v.getLocationOnScreen(it) }
            if (v !== base) canvas.drawColor(0x66000000) // dialog scrim
            canvas.save()
            canvas.translate(loc[0].toFloat(), loc[1].toFloat())
            v.draw(canvas)
            canvas.restore()
        }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun sample(): StoreData {
        val d = StoreData()
        d.works.add(Work(id = "coll", title = "The Dunwich Horror and Others", author = "H. P. Lovecraft", format = "Collection", year = 1963, state = States.CURRENTLY))
        d.works.add(Work(id = "s1", title = "The Colour Out of Space", author = "H. P. Lovecraft", format = "Short story", parentId = "coll", position = 3, state = States.READ, year = 1927))
        d.works.add(Work(id = "s2", title = "The Dunwich Horror", author = "H. P. Lovecraft", format = "Novelette", parentId = "coll", position = 4, state = States.TO_READ))
        d.works.add(Work(id = "show", title = "Severance", author = "Apple TV+", format = "TV show", year = 2022, state = States.CURRENTLY))
        d.addParts("show", listOf(1, 2).map { s ->
            PlannedPart("Season $s", "Season", s, year = 2020 + 2 * s, children = listOf("Good News About Hell", "Half Loop", "In Perpetuity", "Jurassic").mapIndexed { i, t ->
                PlannedPart(t, "Episode", i + 1)
            })
        })
        d.works.filter { it.parentId != null && d.works.first { p -> p.id == it.parentId }.title == "Season 1" }.take(2).forEach { it.state = States.READ }
        d.works.add(Work(id = "movie", title = "Arrival", author = "Denis Villeneuve", format = "Movie", year = 2016, state = States.READ, favorite = true))
        d.works.add(Work(id = "game", title = "Hollow Knight", author = "Team Cherry", format = "Video game", year = 2017, state = States.CURRENTLY, pagesRead = 62))
        d.works.add(Work(id = "manga", title = "Frieren: Beyond Journey's End", author = "Kanehito Yamada", format = "Manga", year = 2020, state = States.TO_READ, pageCount = 140))
        d.works.add(Work(id = "doc", title = "My Octopus Teacher", author = "Pippa Ehrlich", format = "Documentary", year = 2020, state = States.TO_READ))
        d.logs.add(ReadingLog(workId = "s1", finishedDate = "2026-10-06", verdict = "Cosmic dread in a farmhouse", review = "What happened: a meteorite poisons a farm.\n\nHow it made me feel: uneasy for days.", rating = 8.5))
        d.logs.add(ReadingLog(workId = "movie", finishedDate = "2026-09-12", readIn = "Cinema", verdict = "Language as time travel", review = "Quiet, patient and devastating.", rating = 9.5))
        d.logs.add(ReadingLog(workId = "movie", finishedDate = "2025-02-01", rating = 8.0))
        val season1 = d.works.first { it.title == "Season 1" }
        d.logs.add(ReadingLog(workId = season1.id, finishedDate = "2026-03-20", verdict = "Office horror done right", rating = 9.0, readIn = "Apple TV+"))
        d.logs.add(ReadingLog(workId = "game", finishedDate = "2026-05-02", dnf = true, verdict = "Too hard for me, beautiful though", rating = 7.0))
        return d.sanitize()
    }

    private fun start() {
        val vm = ViewModelProvider(rule.activity)[NovaloreViewModel::class.java]
        vm.store.replaceAll(sample())
        rule.setContent { NovaloreTheme(vm.looks.appearance) { NovaloreApp(vm) } }
    }

    // Robolectric never settles once a text field is showing inside a dialog (a test-environment quirk; phones
    // are fine), so each screen with one is the last step of its test and is captured without waiting.

    @Test fun tabsAndShow() {
        start()
        shot("01-library")
        rule.onNodeWithText("Finished").performClick()
        rule.onNodeWithText("All works").performClick()
        shot("02-library-all")
        rule.onNodeWithText("Up next").performClick()
        shot("03-up-next")
        rule.onNodeWithText("Lore").performClick()
        shot("04-diary")
        rule.onNodeWithText("Up next").performClick()
        rule.onAllNodesWithText("Severance").onFirst().performClick()
        shot("05-show-detail")
        rule.onNodeWithText("Fill in parts…").performScrollTo().performClick()
        shot("06-fill-in")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Add numbered parts instead").performClick()
        rule.mainClock.advanceTimeBy(1000)
        shot("07-fill-in-numbered", idle = false)
    }

    @Test fun seasonAndShare() {
        start()
        rule.onNodeWithText("Up next").performClick()
        rule.onAllNodesWithText("Severance").onFirst().performClick()
        rule.onAllNodesWithText("Season 1").onFirst().performScrollTo().performClick()
        shot("08-season-detail")
        rule.onAllNodesWithText("Share review").onFirst().performScrollTo().performClick()
        shot("09-share-dialog")
        rule.onNodeWithText("Review card (cover, stars and verdict in one image)").performClick()
        shot("10-share-card")
    }

    @Test fun rateAnEpisodeFromTheList() {
        start()
        rule.onNodeWithText("Up next").performClick()
        rule.onAllNodesWithText("Severance").onFirst().performClick()
        rule.onAllNodesWithText("Season 1").onFirst().performScrollTo().performClick()
        rule.onNodeWithContentDescription("Rate In Perpetuity").performScrollTo()
        shot("14-season-rate-buttons")
        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription("Rate In Perpetuity").performClick()
        rule.mainClock.advanceTimeBy(1000)
        shot("15-quick-rate", idle = false)
    }

    @Test fun typePartOf() {
        start()
        rule.onNodeWithContentDescription("Add something").performClick()
        rule.onNodeWithText("Title *").performTextInput("The Lottery")
        rule.onNodeWithText("Author *").performTextInput("Shirley Jackson")
        rule.onNodeWithText("Part of (optional)").performTextInput("Dunwich")
        shot("16-part-of-suggestion")
        rule.onNodeWithText("Part of (optional)").performTextClearance()
        rule.onNodeWithText("Part of (optional)").performTextInput("The New Yorker Stories")
        shot("17-part-of-new")
    }

    /** A picture with its edges marked, standing in for a cover (800×560 is a wide game box like Silent Hill's). */
    private fun wideCover(vm: NovaloreViewModel, workId: String, w: Int = 800, h: Int = 560) {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF3B5B8C.toInt())
        val p = android.graphics.Paint().apply { color = 0xFFE04040.toInt() }
        c.drawRect(0f, 0f, w * 0.11f, h.toFloat(), p)
        c.drawRect(w * 0.89f, 0f, w.toFloat(), h.toFloat(), p)
        p.color = 0xFFFFFFFF.toInt(); p.textSize = w * 0.11f
        c.drawText("EDGE TO EDGE", w * 0.14f, h * 0.55f, p)
        val bytes = java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val rel = vm.store.saveCover(workId, java.io.ByteArrayInputStream(bytes))!!
        vm.store.update { d -> d.work(workId)!!.imagePath = rel }
    }

    @Test fun wideCoversAreShownWhole() {
        val vm = ViewModelProvider(rule.activity)[NovaloreViewModel::class.java]
        vm.store.replaceAll(sample())
        wideCover(vm, "game")
        wideCover(vm, "movie", 600, 600)  // square
        wideCover(vm, "s1", 500, 750)     // tall
        // Covers load in the background; warm the caches so the screenshots don't race the load.
        for (id in listOf("game", "movie", "s1")) {
            vm.store.coverFile(vm.store.data.work(id)!!.imagePath)!!.let { ImageCache.load(it, 360); ImageCache.load(it, 900); CoverShapes.measure(it) }
        }
        rule.setContent { NovaloreTheme(vm.looks.appearance) { NovaloreApp(vm) } }
        rule.onNodeWithText("Finished").performClick()
        rule.onNodeWithText("All works").performClick()
        shot("18-wide-cover-grid")
        rule.onAllNodesWithText("Hollow Knight").onFirst().performClick()
        shot("19-wide-cover-detail")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithText("Lore").performClick()
        shot("20-shaped-diary")
        val d = vm.store.data
        val card = reviewCardBitmap(rule.activity, d, d.work("game")!!, d.logsFor("game").first(), DarkSb, fullReview = true)
        File(out, "card-wide-cover.png").outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun yearAndForm() {
        start()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Year in review").performClick()
        shot("11-year-in-review")
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Add something").performClick()
        rule.onNodeWithText("Short story").performClick()
        shot("12-form-format-menu")
        rule.onNodeWithText("Movie").performClick()
        shot("13-form-movie")
    }

    @Test fun reviewAndYearCardsDraw() {
        val d = sample().freeze()
        val ctx = rule.activity
        val movie = d.work("movie")!!
        val log = d.logsFor("movie").first()
        val card = reviewCardBitmap(ctx, d, movie, log, DarkSb, fullReview = true)
        assertTrue(card.width == 1080 && card.height == 1350)
        File(out, "card-review.png").outputStream().use { card.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val year = yearCardBitmap(ctx, d, d.yearStats(2026), DarkSb)
        File(out, "card-year.png").outputStream().use { year.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val light = reviewCardBitmap(ctx, d, d.work("game")!!, d.logsFor("game").first(), Looks.palette(Appearance(accent = "teal"), dark = false), fullReview = false)
        File(out, "card-review-light-nospoilers.png").outputStream().use { light.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
