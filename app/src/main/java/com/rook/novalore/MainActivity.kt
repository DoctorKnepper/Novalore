package com.rook.novalore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var vm: NovaloreViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[NovaloreViewModel::class.java]
        setContent {
            val appearance = vm.looks.appearance
            val dark = appearance.isDark()
            // Status and navigation bar icons follow the app's own light/dark choice, not the phone's.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            NovaloreTheme(appearance) {
                NovaloreApp(vm)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.store.flush() // make sure pending edits are on disk before the process can be killed
    }
}

// ---------------- Navigation ----------------

sealed interface Dest {
    data object Tabs : Dest
    data class Detail(val workId: String) : Dest
    data class WorkForm(val workId: String?, val newId: String, val parentId: String? = null) : Dest
    data class LogEditor(val workId: String, val logId: String?) : Dest
    data object Appearance : Dest
    data object YearReview : Dest

    fun encode(): String = when (this) {
        Tabs -> "T"
        is Detail -> "D|$workId"
        is WorkForm -> "F|${workId ?: ""}|$newId|${parentId ?: ""}"
        is LogEditor -> "L|$workId|${logId ?: ""}"
        Appearance -> "A"
        YearReview -> "Y"
    }

    companion object {
        fun decode(s: String): Dest? {
            val p = s.split('|')
            return when (p[0]) {
                "T" -> Tabs
                "A" -> Appearance
                "Y" -> YearReview
                "D" -> p.getOrNull(1)?.let { Detail(it) }
                "F" -> if (p.size >= 4) WorkForm(p[1].ifEmpty { null }, p[2], p[3].ifEmpty { null }) else null
                "L" -> if (p.size >= 3) LogEditor(p[1], p[2].ifEmpty { null }) else null
                else -> null
            }
        }
    }
}

/** The back stack survives rotation and process death. */
private val BackStackSaver = listSaver<SnapshotStateList<Dest>, String>(
    save = { stack -> stack.map { it.encode() } },
    restore = { saved ->
        val list = saved.mapNotNull { Dest.decode(it) }.toMutableStateList()
        if (list.isEmpty() || list[0] != Dest.Tabs) list.add(0, Dest.Tabs)
        list
    }
)

private fun newId() = java.util.UUID.randomUUID().toString()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovaloreApp(vm: NovaloreViewModel) {
    val store = vm.store
    val data = store.data
    val stack = rememberSaveable(saver = BackStackSaver) { mutableStateListOf<Dest>(Dest.Tabs) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var formDirty by rememberSaveable { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val dest = stack.last()
    val inTabs = dest is Dest.Tabs
    fun push(d: Dest) { stack.add(d) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun back() { if (formDirty) showDiscard = true else pop() }

    BackHandler(enabled = stack.size > 1) { back() }

    // ---- launchers ----
    val exportBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val exportCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportCsv(uri)
    }
    val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readBackup(uri)
    }
    val importCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readCsv(uri)
    }
    val launchImportBackup = { importBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }
    val launchImportCsv = { importCsvLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) }

    // ---- snackbar messages (with Undo for deletes) ----
    val msg = vm.message
    val msgText = msg?.let { stringResource(it.text, *it.args.toTypedArray()) }
    val undoLabel = stringResource(R.string.undo)
    val rateLabel = stringResource(R.string.rate)
    var quickRateFor by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(msg?.id) {
        if (msg != null && msgText != null) {
            val action = when {
                msg.undo != null -> undoLabel
                msg.rateWorkId != null -> rateLabel
                else -> null
            }
            val result = snackbar.showSnackbar(
                message = msgText,
                actionLabel = action,
                duration = if (action != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                msg.undo?.let { vm.undo(it) }
                msg.rateWorkId?.let { quickRateFor = it }
            }
            vm.clearMessage(msg)
        }
    }
    quickRateFor?.let { id ->
        QuickRateDialog(vm, id, onDismiss = { quickRateFor = null }, onFullReview = { wid, logId -> push(Dest.LogEditor(wid, logId)) })
    }

    // ---- dialogs ----
    store.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = { store.notice = null },
            containerColor = Panel,
            title = { Text(stringResource(if (notice == StoreNotice.SAVE_FAILED) R.string.notice_save_title else R.string.notice_load_title), color = Ink) },
            text = {
                Text(
                    stringResource(
                        when (notice) {
                            StoreNotice.RECOVERED_FROM_BACKUP -> R.string.notice_recovered
                            StoreNotice.LOAD_FAILED -> R.string.notice_load_failed
                            StoreNotice.SAVE_FAILED -> R.string.notice_save_failed
                        }
                    ),
                    color = Dim
                )
            },
            confirmButton = { TextButton(onClick = { store.notice = null }) { Text(stringResource(R.string.ok), color = Amber) } }
        )
    }

    vm.pendingBackup?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.cancelBackup() },
            containerColor = Panel,
            title = { Text(stringResource(R.string.replace_library_title), color = Ink) },
            text = { Text(plural(R.plurals.replace_library_body, p.data.works.size, p.data.works.size), color = Dim) },
            confirmButton = { TextButton(onClick = { vm.confirmBackup(); stack.clear(); stack.add(Dest.Tabs) }) { Text(stringResource(R.string.replace), color = Danger) } },
            dismissButton = { TextButton(onClick = { vm.cancelBackup() }) { Text(stringResource(R.string.cancel), color = Dim) } }
        )
    }

    vm.pendingCsv?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.cancelCsv() },
            containerColor = Panel,
            title = { Text(stringResource(R.string.import_csv_title), color = Ink) },
            text = {
                Column {
                    Text(plural(R.plurals.csv_new_works, p.counts.newWorks, p.counts.newWorks), color = Dim)
                    Text(plural(R.plurals.csv_new_logs, p.counts.newLogs, p.counts.newLogs), color = Dim)
                    if (p.counts.matchedWorks > 0) Text(plural(R.plurals.csv_matched, p.counts.matchedWorks, p.counts.matchedWorks), color = Dim)
                    if (p.counts.linked > 0) Text(plural(R.plurals.csv_linked, p.counts.linked, p.counts.linked), color = Dim)
                    if (p.counts.ignoredRows > 0) Text(plural(R.plurals.csv_ignored, p.counts.ignoredRows, p.counts.ignoredRows), color = Faint)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.import_csv_note), color = Faint, fontSize = 13.sp)
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmCsv() }) { Text(stringResource(R.string.import_action), color = Amber) } },
            dismissButton = { TextButton(onClick = { vm.cancelCsv() }) { Text(stringResource(R.string.cancel), color = Dim) } }
        )
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            containerColor = Panel,
            title = { Text(stringResource(R.string.discard_title), color = Ink) },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; formDirty = false; pop() }) { Text(stringResource(R.string.discard), color = Danger) }
            },
            dismissButton = { TextButton(onClick = { showDiscard = false }) { Text(stringResource(R.string.keep_editing), color = Amber) } }
        )
    }

    val title = when (dest) {
        is Dest.Tabs -> stringResource(R.string.app_name)
        is Dest.Detail -> ""
        is Dest.WorkForm -> stringResource(if (dest.workId == null) R.string.add_to_novalore else R.string.edit_work)
        is Dest.LogEditor -> stringResource(if (dest.logId == null) R.string.log_a_read else R.string.edit_read)
        is Dest.Appearance -> stringResource(R.string.appearance)
        is Dest.YearReview -> stringResource(R.string.year_in_review)
    }

    Scaffold(
        containerColor = Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (!inTabs) {
                        IconButton(onClick = { back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = Ink)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bg),
                actions = {
                    if (inTabs) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.menu_more), tint = Ink)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Panel2) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_backup), color = Ink) },
                                    onClick = { menuOpen = false; exportBackupLauncher.launch("novalore-backup.zip") }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_backup), color = Ink) },
                                    onClick = { menuOpen = false; launchImportBackup() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_csv), color = Ink) },
                                    onClick = { menuOpen = false; exportCsvLauncher.launch("novalore-export.csv") }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_csv), color = Ink) },
                                    onClick = { menuOpen = false; launchImportCsv() }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.year_in_review), color = Ink) },
                                    onClick = { menuOpen = false; push(Dest.YearReview) }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.appearance), color = Ink) },
                                    onClick = { menuOpen = false; push(Dest.Appearance) }
                                )
                                val fetch = vm.coverFetch
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (fetch == null) stringResource(R.string.fetch_missing_covers)
                                            else stringResource(R.string.stop_fetching_covers, fetch.done, fetch.total),
                                            color = Ink
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        if (fetch == null) vm.fetchMissingCovers() else vm.stopFetchingCovers()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (inTabs) {
                NavigationBar(containerColor = Panel) {
                    val items = listOf(
                        Triple(R.string.tab_library, Icons.Filled.Home, 0),
                        Triple(R.string.tab_diary, Icons.Filled.Star, 1),
                        Triple(R.string.tab_reading, Icons.Filled.PlayArrow, 2)
                    )
                    items.forEach { (label, icon, i) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            label = { Text(stringResource(label), fontSize = 13.sp) },
                            icon = { Icon(icon, contentDescription = null) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Amber,
                                selectedTextColor = Amber,
                                unselectedIconColor = Dim,
                                unselectedTextColor = Dim,
                                indicatorColor = Panel2
                            )
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (inTabs) {
                FloatingActionButton(
                    onClick = { push(Dest.WorkForm(null, newId())) },
                    containerColor = AmberFill,
                    contentColor = OnAmber
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_story_or_book))
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().background(Bg)) {
            when (dest) {
                is Dest.Tabs -> when (tab) {
                    0 -> LibraryTab(
                        data,
                        onOpen = { push(Dest.Detail(it)) },
                        onAdd = { push(Dest.WorkForm(null, newId())) },
                        onImportCsv = launchImportCsv,
                        onImportBackup = launchImportBackup
                    )
                    1 -> DiaryTab(data, onOpen = { push(Dest.Detail(it)) })
                    else -> ReadingTab(
                        vm, data,
                        onOpen = { push(Dest.Detail(it)) },
                        onFinish = { push(Dest.LogEditor(it, null)) }
                    )
                }
                is Dest.Detail -> {
                    val work = data.work(dest.workId)
                    if (work == null) {
                        LaunchedEffect(dest) { pop() }
                    } else {
                        WorkDetail(
                            vm, work,
                            onOpen = { push(Dest.Detail(it)) },
                            onEdit = { push(Dest.WorkForm(work.id, work.id)) },
                            onNewLog = { push(Dest.LogEditor(work.id, null)) },
                            onEditLog = { logId -> push(Dest.LogEditor(work.id, logId)) },
                            onAddChild = { push(Dest.WorkForm(null, newId(), parentId = work.id)) },
                            onDeleted = { pop() },
                            onReviewOther = { wid, logId -> push(Dest.LogEditor(wid, logId)) }
                        )
                    }
                }
                is Dest.WorkForm -> WorkForm(
                    store, dest.workId, dest.newId, dest.parentId,
                    onDirty = { formDirty = it },
                    onSaved = { savedId, openLog ->
                        formDirty = false
                        pop()
                        if (dest.workId == null) {
                            push(Dest.Detail(savedId))
                            if (openLog) push(Dest.LogEditor(savedId, null))
                        } else if (openLog) {
                            push(Dest.LogEditor(savedId, null))
                        }
                    },
                    onOpenExisting = { id -> formDirty = false; pop(); push(Dest.Detail(id)) }
                )
                is Dest.YearReview -> YearInReviewScreen(data, onOpen = { push(Dest.Detail(it)) })
                is Dest.Appearance -> AppearanceScreen(vm.looks.appearance) { vm.looks.set(it) }
                is Dest.LogEditor -> {
                    val work = data.work(dest.workId)
                    if (work == null) {
                        LaunchedEffect(dest) { pop() }
                    } else {
                        LogEditor(
                            store, work, dest.logId,
                            onDirty = { formDirty = it },
                            onDone = { formDirty = false; pop() }
                        )
                    }
                }
            }
        }
    }
}

// ---------------- Shared bits ----------------

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, placeholder: String) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text(placeholder, color = Faint) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Faint) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink, unfocusedTextColor = Ink,
            focusedBorderColor = Amber, unfocusedBorderColor = Panel2,
            cursorColor = Amber
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
    )
}

/** A filter chip that opens a menu of choices. */
@Composable
private fun <T> MenuChip(
    label: String,
    active: Boolean,
    options: List<Pair<T, String>>,
    onPick: (T) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = active,
            onClick = { open = true },
            label = { Text(label, fontSize = 13.sp) },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = Panel, labelColor = Dim,
                selectedContainerColor = Panel2, selectedLabelColor = Amber
            )
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Panel2) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text, color = Ink) }, onClick = { onPick(value); open = false })
            }
        }
    }
}

/** Filter by kind of media: everything, or only Read / Watch / Play. "" means everything. */
@Composable
private fun TypeChip(group: String, onPick: (String) -> Unit) {
    MenuChip(
        label = if (group.isEmpty()) stringResource(R.string.filter_type) else stringResource(Words.group(MediaGroup.valueOf(group))),
        active = group.isNotEmpty(),
        options = listOf("" to stringResource(R.string.filter_type)) + MediaGroup.entries.map { it.name to stringResource(Words.group(it)) },
        onPick = onPick
    )
}

private fun inGroup(w: Work, group: String) = group.isEmpty() || Formats.groupOf(w.format).name == group

// ---------------- Library ----------------

private enum class LibSort { RECENT, TITLE, AUTHOR, RATING }

private const val STATUS_READ = 0
private const val STATUS_DNF = 1
private const val STATUS_BOTH = 2
private const val STATUS_ALL = 3 // every work, including to-read, reading and paused

@Composable
fun LibraryTab(
    data: StoreData,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onImportCsv: () -> Unit,
    onImportBackup: () -> Unit
) {
    if (data.works.isEmpty()) {
        Welcome(onAdd, onImportCsv, onImportBackup)
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(LibSort.RECENT.name) }
    val sort = LibSort.valueOf(sortName)
    var formatFilter by rememberSaveable { mutableStateOf("") }
    var minRating by rememberSaveable { mutableStateOf(0.0) }
    var decade by rememberSaveable { mutableStateOf(-1) }
    var status by rememberSaveable { mutableStateOf(STATUS_READ) }
    var group by rememberSaveable { mutableStateOf("") }
    var showParts by rememberSaveable { mutableStateOf(false) }

    val works = remember(data, query, favoritesOnly, sort, formatFilter, minRating, decade, status, group, showParts) {
        var list = data.works.filter {
            inGroup(it, group) && (showParts || it.format !in Formats.parts) &&
            when (status) {
                STATUS_READ -> it.state == States.READ
                STATUS_DNF -> it.state == States.DNF
                STATUS_ALL -> true
                else -> it.state == States.READ || it.state == States.DNF
            }
        }
        if (favoritesOnly) list = list.filter { it.favorite }
        if (formatFilter.isNotEmpty()) list = list.filter { it.format == formatFilter }
        if (minRating > 0) list = list.filter { (data.latestLog(it.id)?.rating ?: -1.0) >= minRating }
        if (decade >= 0) list = list.filter { w -> w.year?.let { it / 10 * 10 == decade } == true }
        if (query.isNotBlank()) list = list.filter { data.matches(it, query) }
        when (sort) {
            LibSort.RECENT -> list.sortedByDescending { data.latestLog(it.id)?.finishedDate ?: "" }
            LibSort.TITLE -> list.sortedBy { it.title.lowercase() }
            LibSort.AUTHOR -> list.sortedWith(compareBy({ it.author.lowercase() }, { it.title.lowercase() }))
            LibSort.RATING -> list.sortedByDescending { data.latestLog(it.id)?.rating ?: -1.0 }
        }
    }
    val decades = remember(data) { data.works.mapNotNull { w -> w.year?.let { it / 10 * 10 } }.distinct().sorted() }

    val anyLabel = stringResource(R.string.filter_any)
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SearchField(query, { query = it }, stringResource(R.string.search_library_hint))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = favoritesOnly,
                onClick = { favoritesOnly = !favoritesOnly },
                label = { Text(stringResource(R.string.favorites), fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Favorite, contentDescription = null, tint = if (favoritesOnly) Amber else Faint, modifier = Modifier.size(16.dp))
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Panel, labelColor = Dim,
                    selectedContainerColor = Panel2, selectedLabelColor = Amber
                )
            )
            TypeChip(group) { group = it; formatFilter = "" }
            MenuChip(
                label = when (status) {
                    STATUS_DNF -> stringResource(R.string.filter_dropped)
                    STATUS_BOTH -> stringResource(R.string.filter_read_and_dnf)
                    STATUS_ALL -> stringResource(R.string.filter_all_works)
                    else -> stringResource(R.string.filter_finished)
                },
                active = status != STATUS_READ,
                options = listOf(
                    STATUS_READ to stringResource(R.string.filter_finished),
                    STATUS_DNF to stringResource(R.string.filter_dropped),
                    STATUS_BOTH to stringResource(R.string.filter_read_and_dnf),
                    STATUS_ALL to stringResource(R.string.filter_all_works)
                ),
                onPick = { status = it }
            )
            MenuChip(
                label = if (formatFilter.isEmpty()) stringResource(R.string.filter_format) else formatLabel(formatFilter),
                active = formatFilter.isNotEmpty(),
                options = listOf("" to anyLabel) +
                    Formats.info.filter { group.isEmpty() || it.group.name == group }.map { it.id to formatLabel(it.id) },
                onPick = { formatFilter = it }
            )
            FilterChip(
                selected = showParts,
                onClick = { showParts = !showParts },
                label = { Text(stringResource(R.string.filter_show_parts), fontSize = 13.sp) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Panel, labelColor = Dim,
                    selectedContainerColor = Panel2, selectedLabelColor = Amber
                )
            )
            MenuChip(
                label = if (minRating > 0) stringResource(R.string.filter_rating_at_least, formatRating(minRating)) else stringResource(R.string.filter_rating),
                active = minRating > 0,
                options = listOf(0.0 to anyLabel) + listOf(5.0, 7.0, 9.0).map { it to stringResource(R.string.filter_rating_at_least, formatRating(it)) },
                onPick = { minRating = it }
            )
            if (decades.isNotEmpty()) {
                MenuChip(
                    label = if (decade >= 0) stringResource(R.string.filter_decade_value, decade) else stringResource(R.string.filter_decade),
                    active = decade >= 0,
                    options = listOf(-1 to anyLabel) + decades.map { it to stringResource(R.string.filter_decade_value, it) },
                    onPick = { decade = it }
                )
            }
            MenuChip(
                label = when (sort) {
                    LibSort.RECENT -> stringResource(R.string.sort_recent)
                    LibSort.TITLE -> stringResource(R.string.sort_title)
                    LibSort.AUTHOR -> stringResource(R.string.sort_author)
                    LibSort.RATING -> stringResource(R.string.sort_rating)
                },
                active = false,
                options = listOf(
                    LibSort.RECENT to stringResource(R.string.sort_recent_long),
                    LibSort.TITLE to stringResource(R.string.sort_title_long),
                    LibSort.AUTHOR to stringResource(R.string.sort_author_long),
                    LibSort.RATING to stringResource(R.string.sort_rating_long)
                ),
                onPick = { sortName = it.name }
            )
        }
        if (works.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (status == STATUS_ALL || data.works.any { it.state == States.READ || it.state == States.DNF }) stringResource(R.string.nothing_matches)
                    else stringResource(R.string.no_finished_reads),
                    color = Faint, fontSize = 16.sp, textAlign = TextAlign.Center
                )
            }
        } else {
            Text(plural(R.plurals.works_count, works.size, works.size), color = Faint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
            // Staggered, so each tile can take its cover's shape (tall, square or wide) and still pack neatly.
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalItemSpacing = 12.dp,
                contentPadding = PaddingValues(bottom = 88.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(works, key = { it.id }) { work ->
                    val latest = data.latestLog(work.id)
                    val parent = data.parentOf(work)
                    Column(Modifier.clickable(onClickLabel = stringResource(R.string.open)) { onOpen(work.id) }) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(coverShape(data, work).aspect)
                                .clip(RoundedCornerShape(6.dp))
                        ) {
                            CoverArt(work, Modifier.fillMaxSize(), fallbackPath = data.inheritedCover(work))
                            if (work.favorite) {
                                Icon(
                                    Icons.Filled.Favorite, contentDescription = stringResource(R.string.favorite),
                                    tint = Amber,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(work.title, color = Ink, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (parent != null) {
                            Text(stringResource(R.string.in_parent, parent.title), color = Faint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (work.state != States.READ) {
                            Text(stateLabel(work.state, work.format), color = Faint, fontSize = 11.sp)
                        }
                        if (latest?.rating != null) {
                            Text("★ ${formatRating(latest.rating!!)}", color = Amber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Welcome(onAdd: () -> Unit, onImportCsv: () -> Unit, onImportBackup: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.welcome_title), color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.welcome_body), color = Dim, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = AmberFill, contentColor = OnAmber)) {
            Text(stringResource(R.string.welcome_add))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImportCsv) { Text(stringResource(R.string.welcome_import_csv), color = Ink) }
        TextButton(onClick = onImportBackup) { Text(stringResource(R.string.welcome_import_backup), color = Amber) }
    }
}

// ---------------- Diary ----------------

@Composable
fun DiaryTab(data: StoreData, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sb = LocalSb.current
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("") }
    var shareFor by rememberSaveable { mutableStateOf<String?>(null) }
    shareFor?.let { id ->
        val log = data.logs.firstOrNull { it.id == id }
        val w = log?.let { data.work(it.workId) }
        if (log != null && w != null) ShareReviewDialog(data, w, log) { shareFor = null } else shareFor = null
    }
    val entries = remember(data, query, group) {
        data.logs
            .mapNotNull { log -> data.work(log.workId)?.let { w -> w to log } }
            .filter { (w, log) ->
                inGroup(w, group) && (
                    query.isBlank() || data.matches(w, query) ||
                        log.verdict.contains(query.trim(), ignoreCase = true) || log.review.contains(query.trim(), ignoreCase = true)
                    )
            }
            .sortedWith(compareByDescending<Pair<Work, ReadingLog>> { it.second.finishedDate }.thenBy { it.first.title.lowercase() })
    }
    if (data.logs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.nothing_logged), color = Faint, fontSize = 16.sp)
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        SearchField(query, { query = it }, stringResource(R.string.search_diary_hint))
        Row(Modifier.padding(top = 8.dp)) { TypeChip(group) { group = it } }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.nothing_matches), color = Faint, fontSize = 16.sp)
            }
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            items(entries, key = { it.second.id }) { (work, log) ->
                val parent = data.parentOf(work)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.open)) { onOpen(work.id) }
                ) {
                    Row(Modifier.padding(10.dp)) {
                        CoverThumb(data, work, 66.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(work.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(work.author, color = Dim, fontSize = 13.sp)
                            if (parent != null) Text(stringResource(R.string.in_parent, parent.title), color = Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                logDate(log, Formats.groupOf(work.format)),
                                color = Faint, fontSize = 12.sp
                            )
                            Spacer(Modifier.height(3.dp))
                            StarsRow(log.rating, 12.dp)
                            if (log.verdict.isNotBlank()) {
                                Spacer(Modifier.height(3.dp))
                                Text(log.verdict, color = Ink, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        IconButton(onClick = { shareFor = log.id }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.share_review_of, work.title), tint = Dim)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Up next ----------------

/**
 * In progress, up next and paused, across all media. Episodes, chapters and other parts are left out (their show or
 * manga stands for them), and anything with parts offers "Done with next" to tick off the next one.
 */
@Composable
fun ReadingTab(vm: NovaloreViewModel, data: StoreData, onOpen: (String) -> Unit, onFinish: (String) -> Unit) {
    var group by rememberSaveable { mutableStateOf("") }
    fun pick(state: String) = data.works
        .filter { it.state == state && it.format !in Formats.parts && inGroup(it, group) }
        .sortedBy { it.title.lowercase() }
    val toRead = remember(data, group) { pick(States.TO_READ) }
    val current = remember(data, group) { pick(States.CURRENTLY) }
    val paused = remember(data, group) { pick(States.PAUSED) }
    var progressFor by remember { mutableStateOf<String?>(null) }

    progressFor?.let { id ->
        data.work(id)?.let { w ->
            ProgressDialog(w, onDismiss = { progressFor = null }, onSave = { read, total ->
                vm.saveProgress(w.id, read, total); progressFor = null
            })
        }
    }

    val markNextLabel = stringResource(R.string.mark_next_done)
    val updateLabel = stringResource(R.string.update_progress)

    /** "Done with next" for things with parts, "Update progress" for things counted in pages / episodes / %. */
    fun progressAction(work: Work): Pair<String, () -> Unit>? {
        val next = data.nextUp(work.id)
        return when {
            next != null -> markNextLabel to { vm.markNextDone(work.id) }
            data.childrenOf(work.id).isEmpty() && Formats.of(work.format).unit != ProgressUnit.NONE ->
                updateLabel to { progressFor = work.id }
            else -> null
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Row { TypeChip(group) { group = it } } }
        item { SectionTitle(stringResource(R.string.state_currently)) }
        if (current.isEmpty()) {
            item { Text(stringResource(R.string.nothing_in_progress), color = Faint, fontSize = 14.sp) }
        } else {
            items(current, key = { it.id }) { work ->
                val g = Formats.groupOf(work.format)
                ReadingRow(
                    data, work, onOpen = { onOpen(work.id) },
                    primary = listOfNotNull(
                        progressAction(work),
                        stringResource(R.string.finish) to { onFinish(work.id) }
                    ),
                    overflow = listOf(
                        stringResource(R.string.pause) to { vm.setState(work.id, States.PAUSED) },
                        stringResource(Words.drop(g)) to { vm.setState(work.id, States.DNF) },
                        stringResource(R.string.move_back_to_read) to { vm.setState(work.id, States.TO_READ) },
                        stringResource(R.string.remove) to { vm.deleteWork(work.id) }
                    )
                )
            }
        }
        item {
            Spacer(Modifier.height(10.dp))
            SectionTitle(stringResource(R.string.state_to_read))
        }
        if (toRead.isEmpty()) {
            item { Text(stringResource(R.string.to_read_empty), color = Faint, fontSize = 14.sp) }
        } else {
            items(toRead, key = { it.id }) { work ->
                ReadingRow(
                    data, work, onOpen = { onOpen(work.id) },
                    primary = listOf(
                        stringResource(Words.start(Formats.groupOf(work.format))) to { vm.setState(work.id, States.CURRENTLY) },
                        stringResource(R.string.finish) to { onFinish(work.id) }
                    ),
                    overflow = listOf(stringResource(R.string.remove) to { vm.deleteWork(work.id) })
                )
            }
        }
        if (paused.isNotEmpty()) {
            item {
                Spacer(Modifier.height(10.dp))
                SectionTitle(stringResource(R.string.state_paused))
            }
            items(paused, key = { it.id }) { work ->
                ReadingRow(
                    data, work, onOpen = { onOpen(work.id) },
                    primary = listOf(
                        stringResource(R.string.resume) to { vm.setState(work.id, States.CURRENTLY) },
                        stringResource(R.string.finish) to { onFinish(work.id) }
                    ),
                    overflow = listOf(
                        stringResource(Words.drop(Formats.groupOf(work.format))) to { vm.setState(work.id, States.DNF) },
                        stringResource(R.string.remove) to { vm.deleteWork(work.id) }
                    )
                )
            }
        }
        item { Spacer(Modifier.height(72.dp)) }
    }
}

/** Main actions sit on the card; less common and destructive ones live in the ⋮ menu, away from "Finish". */
@Composable
private fun ReadingRow(
    data: StoreData,
    work: Work,
    onOpen: () -> Unit,
    primary: List<Pair<String, () -> Unit>>,
    overflow: List<Pair<String, () -> Unit>>
) {
    var menu by remember { mutableStateOf(false) }
    val fraction = data.progressFraction(work)
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.open)) { onOpen() }) {
                CoverThumb(data, work, 58.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(work.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(work.author, color = Dim, fontSize = 13.sp)
                    val parent = data.parentOf(work)
                    Text(
                        if (parent != null) stringResource(R.string.format_in_parent, formatLabel(work.format), parent.title) else formatLabel(work.format),
                        color = Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (fraction != null) {
                Spacer(Modifier.height(8.dp))
                ProgressLine(fraction, progressText(data, work))
            }
            data.nextUp(work.id)?.let { next ->
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.next_up, partName(data, work, next)),
                    color = Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                primary.forEachIndexed { i, (label, action) ->
                    TextButton(onClick = action) { Text(label, color = if (i == 0) Amber else Ink, fontSize = 13.sp) }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_actions_for, work.title), tint = Dim)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Panel2) {
                        overflow.forEach { (label, action) ->
                            DropdownMenuItem(text = { Text(label, color = Ink) }, onClick = { menu = false; action() })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun progressText(data: StoreData, work: Work): String {
    val info = Formats.of(work.format)
    if (data.childrenOf(work.id).isNotEmpty()) {
        val r = data.rollupFor(work.id)
        return stringResource(Words.doneCount(info.group), r.read, r.total)
    }
    if (info.unit == ProgressUnit.PERCENT) return stringResource(R.string.progress_percent, work.pagesRead ?: 0)
    val res = Words.unitProgress(info.unit) ?: R.string.progress_pages
    return stringResource(res, work.pagesRead ?: 0, work.pageCount ?: 0)
}
