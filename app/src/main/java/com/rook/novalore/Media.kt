package com.rook.novalore

import androidx.annotation.StringRes

// Media types. Every work has a format; the format decides its group (read / watch / play), what its creator is
// called, how progress is counted, which online service looks it up and what goes inside it.

enum class MediaGroup { READ, WATCH, PLAY }

enum class ProgressUnit { PAGES, CHAPTERS, ISSUES, EPISODES, PERCENT, NONE }

enum class Creator { AUTHOR, DIRECTOR, CREATOR, STUDIO, DEVELOPER }

enum class LookupKind { BOOK, COMIC, MANGA, ANIME, TV, MOVIE, GAME, NONE }

class FormatInfo(
    val id: String,
    @StringRes val label: Int,
    val group: MediaGroup,
    val unit: ProgressUnit,
    val creator: Creator,
    val lookup: LookupKind,
    /** Offered as a "Part of" parent. */
    val container: Boolean = false,
    /** Episodes, chapters and the like: hidden from the Library and Up next unless asked for. */
    val part: Boolean = false,
    /** The format given to things added inside this one. */
    val childFormat: String? = null
)

object Formats {
    val info: List<FormatInfo> = listOf(
        // Read
        FormatInfo("Short story", R.string.format_short_story, MediaGroup.READ, ProgressUnit.PAGES, Creator.AUTHOR, LookupKind.BOOK),
        FormatInfo("Novelette", R.string.format_novelette, MediaGroup.READ, ProgressUnit.PAGES, Creator.AUTHOR, LookupKind.BOOK),
        FormatInfo("Novella", R.string.format_novella, MediaGroup.READ, ProgressUnit.PAGES, Creator.AUTHOR, LookupKind.BOOK),
        FormatInfo("Novel", R.string.format_novel, MediaGroup.READ, ProgressUnit.PAGES, Creator.AUTHOR, LookupKind.BOOK),
        FormatInfo("Nonfiction", R.string.format_nonfiction, MediaGroup.READ, ProgressUnit.PAGES, Creator.AUTHOR, LookupKind.BOOK),
        FormatInfo("Collection", R.string.format_collection, MediaGroup.READ, ProgressUnit.PAGES, Creator.AUTHOR, LookupKind.BOOK, container = true, childFormat = "Short story"),
        FormatInfo("Anthology", R.string.format_anthology, MediaGroup.READ, ProgressUnit.PAGES, Creator.AUTHOR, LookupKind.BOOK, container = true, childFormat = "Short story"),
        FormatInfo("Manga", R.string.format_manga, MediaGroup.READ, ProgressUnit.CHAPTERS, Creator.AUTHOR, LookupKind.MANGA, container = true, childFormat = "Volume"),
        FormatInfo("Comic", R.string.format_comic, MediaGroup.READ, ProgressUnit.ISSUES, Creator.AUTHOR, LookupKind.COMIC, container = true, childFormat = "Issue"),
        FormatInfo("Volume", R.string.format_volume, MediaGroup.READ, ProgressUnit.CHAPTERS, Creator.AUTHOR, LookupKind.NONE, container = true, part = true, childFormat = "Chapter"),
        FormatInfo("Chapter", R.string.format_chapter, MediaGroup.READ, ProgressUnit.NONE, Creator.AUTHOR, LookupKind.NONE, part = true),
        FormatInfo("Issue", R.string.format_issue, MediaGroup.READ, ProgressUnit.NONE, Creator.AUTHOR, LookupKind.NONE, part = true),
        FormatInfo("Book series", R.string.format_book_series, MediaGroup.READ, ProgressUnit.PAGES, Creator.AUTHOR, LookupKind.BOOK, container = true, childFormat = "Novel"),
        FormatInfo("Other", R.string.format_other, MediaGroup.READ, ProgressUnit.PAGES, Creator.AUTHOR, LookupKind.BOOK, container = true, childFormat = "Short story"),
        // Watch
        FormatInfo("Movie", R.string.format_movie, MediaGroup.WATCH, ProgressUnit.NONE, Creator.DIRECTOR, LookupKind.MOVIE),
        FormatInfo("Documentary", R.string.format_documentary, MediaGroup.WATCH, ProgressUnit.NONE, Creator.DIRECTOR, LookupKind.MOVIE),
        FormatInfo("TV show", R.string.format_tv_show, MediaGroup.WATCH, ProgressUnit.EPISODES, Creator.CREATOR, LookupKind.TV, container = true, childFormat = "Season"),
        FormatInfo("Season", R.string.format_season, MediaGroup.WATCH, ProgressUnit.EPISODES, Creator.CREATOR, LookupKind.NONE, container = true, part = true, childFormat = "Episode"),
        FormatInfo("Episode", R.string.format_episode, MediaGroup.WATCH, ProgressUnit.NONE, Creator.CREATOR, LookupKind.NONE, part = true),
        FormatInfo("Anime series", R.string.format_anime_series, MediaGroup.WATCH, ProgressUnit.EPISODES, Creator.STUDIO, LookupKind.ANIME, container = true, childFormat = "Episode"),
        FormatInfo("Anime movie", R.string.format_anime_movie, MediaGroup.WATCH, ProgressUnit.NONE, Creator.STUDIO, LookupKind.ANIME),
        FormatInfo("Movie series", R.string.format_movie_series, MediaGroup.WATCH, ProgressUnit.NONE, Creator.DIRECTOR, LookupKind.MOVIE, container = true, childFormat = "Movie"),
        // Play
        FormatInfo("Video game", R.string.format_video_game, MediaGroup.PLAY, ProgressUnit.PERCENT, Creator.DEVELOPER, LookupKind.GAME, container = true, childFormat = "DLC"),
        FormatInfo("DLC", R.string.format_dlc, MediaGroup.PLAY, ProgressUnit.PERCENT, Creator.DEVELOPER, LookupKind.GAME),
        FormatInfo("Game series", R.string.format_game_series, MediaGroup.PLAY, ProgressUnit.NONE, Creator.DEVELOPER, LookupKind.GAME, container = true, childFormat = "Video game")
    )

    private val byId = info.associateBy { it.id }
    private val fallback = byId.getValue("Other")

    val all: List<String> = info.map { it.id }

    /** Formats offered as a "Part of" parent. Any work that already has things inside it also counts. */
    val containers: Set<String> = info.filter { it.container }.mapTo(HashSet()) { it.id }

    val parts: Set<String> = info.filter { it.part }.mapTo(HashSet()) { it.id }

    fun of(format: String): FormatInfo = byId[format] ?: fallback

    fun groupOf(format: String): MediaGroup = of(format).group

    fun inGroup(group: MediaGroup): List<FormatInfo> = info.filter { it.group == group }

    /** What a typed-in "Part of" becomes when nothing in the library matches it. */
    fun parentFor(format: String): String = when (format) {
        "Short story", "Novelette", "Novella" -> "Collection"
        "Volume", "Chapter" -> "Manga"
        "Issue" -> "Comic"
        "Season", "Episode" -> "TV show"
        "DLC" -> "Video game"
        else -> when (groupOf(format)) {
            MediaGroup.READ -> "Book series"
            MediaGroup.WATCH -> "Movie series"
            MediaGroup.PLAY -> "Game series"
        }
    }

    /** What a new thing added inside [parentFormat] defaults to. */
    fun childOf(parentFormat: String): String =
        of(parentFormat).childFormat ?: when (groupOf(parentFormat)) {
            MediaGroup.READ -> "Short story"
            MediaGroup.WATCH -> "Episode"
            MediaGroup.PLAY -> "DLC"
        }
}

/** Display text that depends on the group: "Read" / "Watched" / "Played" and so on. */
object Words {
    @StringRes fun state(state: String, group: MediaGroup): Int = when (state) {
        States.READ -> pick(group, R.string.state_read, R.string.state_watched, R.string.state_played)
        States.CURRENTLY -> pick(group, R.string.state_reading, R.string.state_watching, R.string.state_playing)
        States.PAUSED -> R.string.state_paused
        States.DNF -> pick(group, R.string.state_dnf, R.string.state_dropped, R.string.state_dropped)
        else -> pick(group, R.string.state_want_read, R.string.state_want_watch, R.string.state_want_play)
    }

    @StringRes fun start(group: MediaGroup): Int = pick(group, R.string.start_reading, R.string.start_watching, R.string.start_playing)

    @StringRes fun drop(group: MediaGroup): Int = pick(group, R.string.did_not_finish_action, R.string.drop_action, R.string.drop_action)

    @StringRes fun didntFinishCheckbox(group: MediaGroup): Int =
        pick(group, R.string.didnt_finish_checkbox, R.string.dropped_checkbox, R.string.dropped_checkbox)

    @StringRes fun finishedLabel(group: MediaGroup): Int = pick(group, R.string.finished_label, R.string.watched_label, R.string.played_label)

    @StringRes fun where(group: MediaGroup): Int = pick(group, R.string.read_in_value, R.string.watched_on_value, R.string.played_on_value)

    @StringRes fun whereField(group: MediaGroup): Int = pick(group, R.string.field_read_in, R.string.field_watched_on, R.string.field_played_on)

    @StringRes fun sharedDone(group: MediaGroup): Int = pick(group, R.string.share_finished_on, R.string.share_watched_on, R.string.share_played_on)

    @StringRes fun sharedStopped(group: MediaGroup): Int = pick(group, R.string.share_stopped_on, R.string.share_dropped_on, R.string.share_dropped_on)

    @StringRes fun doneCount(group: MediaGroup): Int = pick(group, R.string.progress_read, R.string.progress_watched, R.string.progress_played)

    @StringRes fun group(group: MediaGroup): Int = pick(group, R.string.group_read, R.string.group_watch, R.string.group_play)

    @StringRes fun creator(c: Creator, required: Boolean): Int = when (c) {
        Creator.AUTHOR -> if (required) R.string.field_author else R.string.field_author_plain
        Creator.DIRECTOR -> R.string.field_director
        Creator.CREATOR -> R.string.field_creator
        Creator.STUDIO -> R.string.field_studio
        Creator.DEVELOPER -> R.string.field_developer
    }

    /** "%1$d of %2$d pages" etc. Null for units with no count. */
    @StringRes fun unitProgress(u: ProgressUnit): Int? = when (u) {
        ProgressUnit.PAGES -> R.string.progress_pages
        ProgressUnit.CHAPTERS -> R.string.progress_chapters
        ProgressUnit.ISSUES -> R.string.progress_issues
        ProgressUnit.EPISODES -> R.string.progress_episodes
        ProgressUnit.PERCENT -> R.string.progress_percent
        ProgressUnit.NONE -> null
    }

    /** Label for the "how long is it" field on the form, or null when the format has none. */
    @StringRes fun totalField(u: ProgressUnit): Int? = when (u) {
        ProgressUnit.PAGES -> R.string.field_pages
        ProgressUnit.CHAPTERS -> R.string.field_chapters
        ProgressUnit.ISSUES -> R.string.field_issues
        ProgressUnit.EPISODES -> R.string.field_episodes
        ProgressUnit.PERCENT, ProgressUnit.NONE -> null
    }

    @StringRes fun doneField(u: ProgressUnit): Int = when (u) {
        ProgressUnit.PAGES -> R.string.field_pages_read
        ProgressUnit.CHAPTERS -> R.string.field_chapters_read
        ProgressUnit.ISSUES -> R.string.field_issues_read
        ProgressUnit.EPISODES -> R.string.field_episodes_watched
        ProgressUnit.PERCENT, ProgressUnit.NONE -> R.string.field_percent_done
    }

    @StringRes private fun pick(group: MediaGroup, read: Int, watch: Int, play: Int): Int = when (group) {
        MediaGroup.READ -> read
        MediaGroup.WATCH -> watch
        MediaGroup.PLAY -> play
    }
}
