# Novalore 3.0 (formerly Storybox)

- **New name and icon.** The app is now **Novalore**: a nova (a star blazing into light, for your ratings) plus lore (every story you take in). The icon is a hand of cards (your reviews, spread out) under a glowing rating star, with a one-colour version for Android themed icons.
- **New app ID:** `com.rook.novalore` (was `com.rook.storybox`). Android treats it as a new app, so it installs alongside Storybox. Move your library across with Storybox's *Export backup*, then Novalore's *Restore a Novalore or Storybox backup* on the welcome screen (or *Import backup* in the gear menu). Storybox CSV exports import as well.
- Backups and exports are now named `novalore-backup.zip` and `novalore-export.csv`; the file formats are unchanged.

# Storybox 2.0: every kind of story

- **Media types.** Formats are grouped into Read (stories, books, nonfiction, manga, comics, volumes, chapters, issues), Watch (movies, documentaries, TV shows, seasons, episodes, anime series and movies) and Play (video games, DLC). Wording follows the type: Watched / Want to play / Dropped, Director / Studio / Developer, "Watched on" / "Played on", and progress in pages, chapters, issues, episodes or percent. Existing libraries carry over unchanged as Read.
- **Shows, seasons and episodes** (and manga, volumes and chapters) are each a work of their own, so each can be reviewed and re-reviewed separately. **Fill in parts…** adds every season and episode from TVmaze, or a numbered run (episodes 1–12, volumes 1–41). Parts can be ticked off with one tap; finishing every episode finishes the season. Up next shows "Next: Season 2 › 4. …" with **Done with next**.
- **Lookup by type:** TVmaze (TV, anime), AniList (anime, manga), Wikipedia (movies, documentaries, games), Open Library / Google Books (books, comics). No accounts or keys needed.
- **Sharing:** a spoiler switch (share only the rating and verdict) and a choice of picture: the cover, an optional review card (cover, stars and verdict in one 4:5 image), or none. Choices are remembered.
- **Year in review** (gear menu): totals, average rating, revisits, by type, by month, top rated, most reviewed creators, and a shareable summary picture.
- **Filters:** Library, Diary and Up next filter by type. Episodes, chapters and the like are hidden from the Library unless *Episodes & chapters* is on.
- **Tests:** 42 local tests, including Robolectric screen walk-throughs that render the real UI (`ROBOLECTRIC_DEPS` can point at pre-downloaded Android jars).

# Storybox 1.1: changes

The rating and stats system is unchanged: same 10 stars, same half steps, same look.

## Before you install

- **Your existing library carries over.** v1's `data.json` is read as-is.
- **Back up first anyway.** Use v1's *Export backup*. If you build a signed *release* APK, Android won't install it over the debug-signed v1. You'd need to uninstall v1, then use *Import backup*.
- **New installs start empty.** The Lovecraft seed is no longer bundled with the app. To load a reading log on a fresh install, use **Import CSV**.

## Data safety fixes
| Problem in v1 | Fix |
|---|---|
| A crafted backup could write outside the covers folder (`covers/../data.json`) | Only `data.json` and `covers/<file>` entries are accepted. Paths are checked after resolving. Imported `imagePath`s are validated before any read or delete. Size and entry-count limits guard against zip bombs. |
| A damaged `data.json` was replaced by an empty library on the next edit | Each save keeps `data.json.bak`. A damaged file is renamed aside, never overwritten. The app restores from `.bak` when it can and tells the user either way. Save failures are reported instead of swallowed. |
| Background cover fetching could overwrite edits made at the same time | Updates are serialized. Disk writes run on one background thread and are flushed when the app goes to the background. Tested with 800 concurrent edits: none lost. |
| Every new user got your personal reviews, plus 17 network calls on first launch | The seed was removed. **⋮ → Fetch missing covers** does the cover pass on demand, with progress and a stop option. |
| Covers flickered while scrolling (cache key mismatch) and decoded at 900px in the grid | Cache lookups and stores now use the same key. Lists decode at 360px, the detail screen at 900px, and the cache is sized from the device's memory. |
| Import extracted images before you confirmed, and old covers were never cleaned up | Backups are staged first. *Cancel* deletes the staging folder; *Replace* moves the covers in, then removes unused ones. Replaced or abandoned covers are deleted, and unused files are swept at startup. |

## Features
- **Collections:** any work can be *Part of* a Collection, Anthology or Other, with an optional position. The collection page shows its contents, "N of M stories read", and the average of the stories' latest ratings (computed from existing ratings; the rating system itself is unchanged). There's also *Add a story to this collection*. Stories without a cover use the collection's. Loops are prevented.
- **Long books:** page count, pages-read progress (*Update progress*) and a start date (set when you start reading). Online lookup also fills in the page count.
- **New states:** *Paused* and *Didn't finish*. The log editor has an "I didn't finish this" checkbox.
- **Review prompts:** one tap inserts *Story notes* (your "What happened / Sticking image / How it made me feel" format), *Pros & cons* or *Spoiler-free*.
- **Search and filters:** search covers reviews, verdicts and collection titles. The Library adds Read / Didn't finish, Format, Rating ★5+/7+/9+ and Decade filters. The Diary has its own search.
- **CSV import:** reads Storybox's own export and Goodreads/StoryGraph exports (their 5-star ratings are converted ×2). It shows a preview first, only ever adds, and matches existing works by title + author so nothing is duplicated.
- **Safer actions:** adding a duplicate is caught, deletes have *Undo*, leaving a form with unsaved changes asks first, and "Remove" moved into a ⋮ menu away from "Finish".
- **Lookup:** shows a clear "offline" message and adds Google Books results when Open Library has no covers.

## Engineering
- A ViewModel owns the store. The back stack and every form field survive rotation and process death (`rememberSaveable`). Back from a story returns to its collection.
- Data file format v2 is versioned, and files from a newer version are refused rather than mangled. Lookups are indexed, so large libraries stay fast.
- Release builds use R8 with resource shrinking and optional signing from `keystore.properties` (git-ignored). `versionCode` is 2.
- Backup rules: only `data.json`, `.bak` and `covers/` go to Google backup and device transfer.
- All UI text is in `strings.xml`, with plurals, ready for translation. Dates follow the device locale.
- Follows the system light/dark setting; the dark theme is the original. Light-theme text contrast is at least 4.5:1.
- Accessibility: bottom-nav icons; TalkBack labels for covers, favorites and menus; the star display reads "Rated 7 out of 10"; the rating input is one adjustable control for TalkBack (same look and touch behaviour); headings are marked; radio and checkbox rows are fully tappable.
- Tests: `app/src/test/.../LogicTest.kt` has 18 JUnit tests (CSV round-trip, Goodreads import, path safety, collections, undo).

## Not done, and why
- **Target API 36:** Google Play has required it for new apps and updates since Aug 31, 2026, and extensions run to Nov 1, 2026. It needs a newer Android Gradle Plugin than 8.7.3. I couldn't verify version numbers from here, so use Android Studio's **Tools → AGP Upgrade Assistant**, then set `compileSdk`/`targetSdk = 36`. Dependency versions (Compose BOM etc.) are unchanged for the same reason.
- **Room, Coil, Navigation Compose, kotlinx.serialization:** not added. They're new dependencies I couldn't build or test here. The problems they would solve (indexed lookups, image caching, state that survives restarts, versioned file format) are fixed with the existing stack.

## How this was checked
I couldn't run a full Android build: Google's Maven repository isn't reachable from the environment I worked in.
- The pure-Kotlin logic (`Model.kt`, `Csv.kt`, `SafeFiles.kt`) was compiled with Kotlin 2.0.21 and passes all 18 unit tests.
- The storage layer (`Store.kt`, `Json.kt`, `Net.kt`) was compiled against JVM stand-ins for the Android classes. It passes 35 integration checks: v1 file loading, corrupt-file recovery, a malicious backup, concurrent writes, backup round-trip and orphan cleanup.
- The Compose UI files were reviewed line by line against the library versions in use, but they have **not** been compiled. Build in Android Studio and click through each screen before release.
