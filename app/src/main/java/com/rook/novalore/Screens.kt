package com.rook.novalore

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val fieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Ink, unfocusedTextColor = Ink,
        focusedBorderColor = Amber, unfocusedBorderColor = Panel2,
        focusedLabelColor = Amber, unfocusedLabelColor = Faint,
        cursorColor = Amber
    )

@Composable
private fun primaryButtonColors() = ButtonDefaults.buttonColors(containerColor = AmberFill, contentColor = OnAmber)

private fun digits(s: String, max: Int) = s.filter { it.isDigit() }.take(max)

private const val CHILD_PAGE = 100

// ---------------- Work detail ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkDetail(
    vm: NovaloreViewModel,
    work: Work,
    onOpen: (String) -> Unit,
    onEdit: () -> Unit,
    onNewLog: () -> Unit,
    onEditLog: (String) -> Unit,
    onAddChild: () -> Unit,
    onDeleted: () -> Unit,
    onReviewOther: (workId: String, logId: String?) -> Unit
) {
    val store = vm.store
    val data = store.data
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sb = LocalSb.current
    val logs = data.logsFor(work.id)
    val parent = data.parentOf(work)
    val children = data.childrenOf(work.id)
    val isContainer = data.isContainer(work)
    val group = Formats.groupOf(work.format)
    val info = Formats.of(work.format)
    var showAllChildren by remember { mutableStateOf(false) }
    var showImageOptions by remember { mutableStateOf(false) }
    var showLookup by remember { mutableStateOf(false) }
    var showProgress by remember { mutableStateOf(false) }
    var showFill by remember { mutableStateOf(false) }
    var shareFor by rememberSaveable { mutableStateOf<String?>(null) }
    var rateFor by rememberSaveable { mutableStateOf<String?>(null) }
    val couldNotUseImage = stringResource(R.string.could_not_use_image)
    val couldNotDownload = stringResource(R.string.could_not_download_cover)

    fun setImage(rel: String, yearFromLookup: Int? = null, pagesFromLookup: Int? = null, creatorFromLookup: String? = null) {
        val old = store.data.work(work.id)?.imagePath
        store.update { d ->
            d.work(work.id)?.let { w ->
                w.imagePath = rel
                if (w.author.isBlank() && !creatorFromLookup.isNullOrBlank()) w.author = creatorFromLookup
                if (w.year == null && yearFromLookup != null) w.year = yearFromLookup
                if (w.pageCount == null && pagesFromLookup != null) w.pageCount = pagesFromLookup
            }
        }
        if (old != null && old != rel) store.deleteCover(old)
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val rel = store.copyPickedImage(uri, work.id)
                withContext(Dispatchers.Main) {
                    if (rel != null) setImage(rel) else Toast.makeText(context, couldNotUseImage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    if (showImageOptions) {
        Dialog(onDismissRequest = { showImageOptions = false }) {
            Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.change_image), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
                    TextButton(onClick = { showImageOptions = false; showLookup = true }) {
                        Text(stringResource(R.string.fetch_online), color = Amber, fontSize = 16.sp)
                    }
                    TextButton(onClick = {
                        showImageOptions = false
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Text(stringResource(R.string.choose_from_device), color = Amber, fontSize = 16.sp)
                    }
                    if (work.imagePath != null) {
                        TextButton(onClick = {
                            val old = work.imagePath
                            store.update { d -> d.work(work.id)?.imagePath = null }
                            store.deleteCover(old)
                            showImageOptions = false
                        }) {
                            Text(stringResource(R.string.remove_image), color = Danger, fontSize = 16.sp)
                        }
                    }
                    TextButton(onClick = { showImageOptions = false }) {
                        Text(stringResource(R.string.cancel), color = Dim, fontSize = 16.sp)
                    }
                }
            }
        }
    }

    if (showLookup) {
        LookupDialog(
            initialTitle = work.title,
            initialAuthor = work.author,
            format = work.format,
            onDismiss = { showLookup = false },
            onPick = { candidate ->
                showLookup = false
                val url = candidate.coverUrl
                if (url != null) {
                    scope.launch(Dispatchers.IO) {
                        val rel = downloadCover(store, work.id, url)
                        withContext(Dispatchers.Main) {
                            if (rel != null) setImage(rel, candidate.year, candidate.pageCount, candidate.author)
                            else Toast.makeText(context, couldNotDownload, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    store.update { d ->
                        d.work(work.id)?.let { w ->
                            if (w.year == null) w.year = candidate.year
                            if (w.pageCount == null) w.pageCount = candidate.pageCount
                            if (w.author.isBlank()) w.author = candidate.author
                        }
                    }
                }
            }
        )
    }

    shareFor?.let { id ->
        val log = logs.firstOrNull { it.id == id }
        if (log != null) ShareReviewDialog(data, work, log) { shareFor = null } else shareFor = null
    }

    rateFor?.let { id ->
        QuickRateDialog(vm, id, onDismiss = { rateFor = null }, onFullReview = onReviewOther)
    }

    if (showFill) {
        FillInDialog(vm, work, onDismiss = { showFill = false })
    }

    if (showProgress) {
        ProgressDialog(work, onDismiss = { showProgress = false }, onSave = { read, total ->
            vm.saveProgress(work.id, read, total); showProgress = false
        })
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(250.dp)
                .clip(RoundedCornerShape(10.dp))
        ) {
            CoverArt(
                work, Modifier.fillMaxSize(),
                fallbackPath = data.inheritedCover(work),
                maxDim = 900,
                contentDescription = stringResource(R.string.cover_of, work.title)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(work.title, color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Bold, lineHeight = 30.sp)
        Text(work.author, color = Dim, fontSize = 17.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(
                formatLabel(work.format),
                work.year?.toString(),
                work.pageCount?.let { lengthLabel(info.unit, it) }
            ).joinToString(" · "),
            color = Faint, fontSize = 14.sp
        )
        if (parent != null) {
            Text(
                stringResource(R.string.in_parent, parent.title),
                color = Amber, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(onClickLabel = stringResource(R.string.open_collection, parent.title)) { onOpen(parent.id) }
                    .padding(vertical = 8.dp)
            )
        }
        if (work.startedDate.isNotBlank()) {
            Text(stringResource(R.string.started_on, fmtDate(work.startedDate)), color = Faint, fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { store.update { d -> d.work(work.id)?.let { it.favorite = !it.favorite } } }) {
                Icon(
                    if (work.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(if (work.favorite) R.string.remove_from_favorites else R.string.add_to_favorites),
                    tint = if (work.favorite) Amber else Faint
                )
            }
            Text(
                stringResource(if (work.favorite) R.string.favorite else R.string.mark_as_favorite),
                color = if (work.favorite) Amber else Dim, fontSize = 14.sp
            )
            Spacer(Modifier.weight(1f))
            Text(stateLabel(work.state, work.format), color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        // Rate it right here: tapping the stars saves straight away (starting a review if there isn't one).
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.your_rating), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        RatingInput(logs.firstOrNull()?.rating) { vm.quickRate(work.id, it) }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onNewLog, colors = primaryButtonColors()) {
                Text(stringResource(if (logs.isEmpty()) R.string.write_review else R.string.log_a_reread))
            }
            OutlinedButton(onClick = onEdit) { Text(stringResource(R.string.edit), color = Ink) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (work.state) {
                States.TO_READ -> TextButton(onClick = { vm.setState(work.id, States.CURRENTLY) }) {
                    Text(stringResource(Words.start(group)), color = Amber)
                }
                States.CURRENTLY -> {
                    if (children.isEmpty() && info.unit != ProgressUnit.NONE) {
                        TextButton(onClick = { showProgress = true }) { Text(stringResource(R.string.update_progress), color = Amber) }
                    }
                    TextButton(onClick = { vm.setState(work.id, States.PAUSED) }) { Text(stringResource(R.string.pause), color = Amber) }
                    TextButton(onClick = { vm.setState(work.id, States.DNF) }) { Text(stringResource(Words.drop(group)), color = Amber) }
                    TextButton(onClick = { vm.setState(work.id, States.TO_READ) }) { Text(stringResource(R.string.move_back_to_read), color = Amber) }
                }
                States.PAUSED -> {
                    TextButton(onClick = { vm.setState(work.id, States.CURRENTLY) }) { Text(stringResource(R.string.resume), color = Amber) }
                    TextButton(onClick = { vm.setState(work.id, States.DNF) }) { Text(stringResource(Words.drop(group)), color = Amber) }
                }
                States.DNF -> TextButton(onClick = { vm.setState(work.id, States.CURRENTLY) }) {
                    Text(stringResource(R.string.resume), color = Amber)
                }
                else -> {}
            }
            TextButton(onClick = { showImageOptions = true }) { Text(stringResource(R.string.change_image), color = Dim) }
            TextButton(onClick = { vm.deleteWork(work.id); onDeleted() }) { Text(stringResource(R.string.delete), color = Danger) }
        }

        val fraction = data.progressFraction(work)
        if (fraction != null && (work.state != States.READ || children.isNotEmpty())) {
            Spacer(Modifier.height(8.dp))
            ProgressLine(fraction, progressText(data, work))
        }

        if (isContainer) {
            Spacer(Modifier.height(18.dp))
            SectionTitle(stringResource(R.string.contents))
            Spacer(Modifier.height(4.dp))
            if (children.isEmpty()) {
                Text(stringResource(R.string.contents_empty), color = Faint, fontSize = 14.sp)
            } else {
                val r = data.rollupFor(work.id)
                // The progress bar above already shows how many are done, unless it's hidden for finished works.
                if (work.state == States.READ) {
                    Text(stringResource(Words.doneCount(group), r.read, r.total), color = Dim, fontSize = 14.sp)
                }
                // One line per level, e.g. "Seasons · ★ 9 average · 1 of 2 rated", then "Episodes · …".
                data.ratingsByLevel(work.id).filter { it.avg != null }.forEach { l ->
                    Text(
                        stringResource(
                            R.string.level_rating, pluralLabel(l.format),
                            formatRating(Math.round(l.avg!! * 10) / 10.0), l.rated, l.total
                        ),
                        color = Dim, fontSize = 14.sp
                    )
                }
                data.nextUp(work.id)?.let { next ->
                    Text(stringResource(R.string.next_up, partName(data, work, next)), color = Faint, fontSize = 13.sp)
                }
                Text(stringResource(R.string.contents_hint), color = Faint, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                // Long-running shows can have hundreds of episodes; show a page of them until asked for the rest.
                val shown = if (showAllChildren) children else children.take(CHILD_PAGE)
                shown.forEach { child ->
                    ChildRow(
                        data, child,
                        onOpen = { onOpen(child.id) },
                        onToggleDone = { vm.markDone(child.id, it) },
                        onRate = { rateFor = child.id }
                    )
                }
                if (!showAllChildren && children.size > CHILD_PAGE) {
                    TextButton(onClick = { showAllChildren = true }) {
                        Text(stringResource(R.string.show_all_n, children.size), color = Amber)
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAddChild) {
                    Text(stringResource(R.string.add_story_to_collection, formatLabel(Formats.childOf(work.format)).lowercase()), color = Amber)
                }
                TextButton(onClick = { showFill = true }) { Text(stringResource(R.string.fill_in), color = Amber) }
                if (data.nextUp(work.id) != null) {
                    TextButton(onClick = { vm.markAllDone(work.id) }) { Text(stringResource(R.string.mark_all_done), color = Dim) }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle(stringResource(R.string.reading_log))
        Spacer(Modifier.height(8.dp))
        if (logs.isEmpty()) {
            Text(stringResource(R.string.no_reads_logged), color = Faint, fontSize = 15.sp)
        } else {
            logs.forEach { log ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clickable(onClickLabel = stringResource(R.string.edit)) { onEditLog(log.id) }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                logDate(log, group),
                                color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.weight(1f))
                            StarsRow(log.rating, 12.dp)
                        }
                        if (log.verdict.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(log.verdict, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        if (log.review.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(log.review, color = Dim, fontSize = 15.sp, lineHeight = 21.sp)
                        }
                        if (log.readIn.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(stringResource(Words.where(group), log.readIn), color = Faint, fontSize = 13.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.tap_to_edit), color = Faint, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { shareFor = log.id }) {
                                Icon(Icons.Filled.Share, contentDescription = null, tint = Amber, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.share_review), color = Amber, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- Add / edit work form ----------------

@Composable
fun WorkForm(
    store: NovaloreStore,
    workId: String?,
    newId: String,
    presetParentId: String?,
    onDirty: (Boolean) -> Unit,
    onSaved: (String, Boolean) -> Unit,
    onOpenExisting: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = store.data
    val existing = remember(workId) { workId?.let { store.data.work(it) } }
    val formId = workId ?: newId
    val presetParent = remember(presetParentId) { presetParentId?.let { store.data.work(it) } }
    val originalImage = existing?.imagePath

    val initTitle = existing?.title ?: ""
    val initAuthor = existing?.author ?: presetParent?.author ?: ""
    val initFormat = existing?.format ?: presetParent?.let { Formats.childOf(it.format) } ?: "Short story"
    val initYear = existing?.year?.toString() ?: ""
    val initPages = existing?.pageCount?.toString() ?: ""
    val initPosition = existing?.position?.toString() ?: ""
    val initParent = existing?.parentId ?: presetParent?.id
    val initState = existing?.state ?: States.TO_READ

    var title by rememberSaveable { mutableStateOf(initTitle) }
    var author by rememberSaveable { mutableStateOf(initAuthor) }
    var format by rememberSaveable { mutableStateOf(initFormat) }
    var yearText by rememberSaveable { mutableStateOf(initYear) }
    var pagesText by rememberSaveable { mutableStateOf(initPages) }
    var positionText by rememberSaveable { mutableStateOf(initPosition) }
    var parentId by rememberSaveable { mutableStateOf(initParent) }
    val initPartOf = initParent?.let { store.data.work(it)?.title } ?: ""
    var partOfText by rememberSaveable { mutableStateOf(initPartOf) }
    var imagePath by rememberSaveable { mutableStateOf(originalImage) }
    var destState by rememberSaveable { mutableStateOf(initState) }
    var sessionImage by rememberSaveable { mutableStateOf<String?>(null) }
    var showError by remember { mutableStateOf(false) }
    var showLookup by remember { mutableStateOf(false) }
    var formatMenu by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf<Work?>(null) }
    var saved by remember { mutableStateOf(false) }

    val info = Formats.of(format)
    val group = info.group
    val authorRequired = group == MediaGroup.READ
    val dirty = title != initTitle || author != initAuthor || format != initFormat || yearText != initYear ||
        pagesText != initPages || positionText != initPosition || partOfText != initPartOf ||
        imagePath != originalImage || destState != initState
    LaunchedEffect(dirty) { onDirty(dirty) }

    // Whatever way the form is left (save, back, cancel), don't leave an unused picked image behind.
    DisposableEffect(Unit) {
        onDispose {
            val changingConfig = (context as? Activity)?.isChangingConfigurations == true
            if (!saved && !changingConfig) {
                val s = sessionImage
                if (s != null && s != originalImage) store.deleteCover(s)
            }
        }
    }

    fun adoptImage(rel: String) {
        val prev = sessionImage
        if (prev != null && prev != originalImage) store.deleteCover(prev)
        sessionImage = rel
        imagePath = rel
    }

    val couldNotUseImage = stringResource(R.string.could_not_use_image)
    val couldNotDownload = stringResource(R.string.could_not_download_cover)
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val rel = store.copyPickedImage(uri, formId)
                withContext(Dispatchers.Main) {
                    if (rel != null) adoptImage(rel) else Toast.makeText(context, couldNotUseImage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun doSave() {
        val year = yearText.toIntOrNull()
        val pages = if (Words.totalField(Formats.of(format).unit) != null) pagesText.toIntOrNull()?.takeIf { it > 0 } else null
        val typedParent = partOfText.trim()
        val position = if (typedParent.isNotEmpty()) positionText.toIntOrNull() else null
        val wasState = existing?.state
        val hadLogs = existing?.let { store.data.logsFor(it.id).isNotEmpty() } ?: false
        store.update { d ->
            val w = if (existing == null) {
                Work(id = newId).also { d.works.add(it) }
            } else {
                d.work(existing.id) ?: return@update
            }
            // A suggestion that was picked is used as is; anything typed is matched by title or created.
            val picked = parentId?.takeIf { p -> d.work(p)?.title?.trim()?.equals(typedParent, ignoreCase = true) == true }
            val chosen = picked ?: d.resolveParent(typedParent, formId, format, author, destState)
            // Never allow a work to end up inside itself.
            val safeParent = chosen?.takeIf { p -> p != formId && d.work(p) != null && p !in d.descendantIds(formId) }
            w.title = title.trim()
            w.author = author.trim()
            w.format = format
            w.year = year
            w.pageCount = pages
            w.parentId = safeParent
            w.position = position
            w.imagePath = imagePath
            d.setState(w.id, destState)
        }
        if (originalImage != null && originalImage != imagePath) store.deleteCover(originalImage)
        saved = true
        onDirty(false)
        val openLog = destState == States.READ && (existing == null || wasState != States.READ || !hadLogs)
        onSaved(formId, openLog)
    }

    if (showLookup) {
        LookupDialog(
            initialTitle = title,
            initialAuthor = author,
            format = format,
            onDismiss = { showLookup = false },
            onPick = { candidate ->
                showLookup = false
                if (candidate.year != null && yearText.isBlank()) yearText = candidate.year.toString()
                if (candidate.pageCount != null && pagesText.isBlank()) pagesText = candidate.pageCount.toString()
                if (candidate.author.isNotBlank() && author.isBlank()) author = candidate.author
                val url = candidate.coverUrl
                if (url != null) {
                    scope.launch(Dispatchers.IO) {
                        val rel = downloadCover(store, formId, url)
                        withContext(Dispatchers.Main) {
                            if (rel != null) adoptImage(rel) else Toast.makeText(context, couldNotDownload, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        )
    }

    duplicate?.let { dup ->
        AlertDialog(
            onDismissRequest = { duplicate = null },
            containerColor = Panel,
            title = { Text(stringResource(R.string.duplicate_title), color = Ink) },
            text = { Text(stringResource(R.string.duplicate_body, dup.title, dup.author), color = Dim) },
            confirmButton = { TextButton(onClick = { duplicate = null; doSave() }) { Text(stringResource(R.string.add_anyway), color = Amber) } },
            dismissButton = {
                TextButton(onClick = { duplicate = null; saved = true; onDirty(false); onOpenExisting(dup.id) }) {
                    Text(stringResource(R.string.open_existing), color = Dim)
                }
            }
        )
    }

    val parent = parentId?.let { data.work(it) }
    val parentOptions = remember(data, workId) { data.parentOptions(workId) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text(stringResource(R.string.field_title)) }, singleLine = true,
            isError = showError && title.isBlank(),
            colors = fieldColors, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = author, onValueChange = { author = it },
            label = { Text(stringResource(Words.creator(info.creator, authorRequired))) }, singleLine = true,
            isError = showError && authorRequired && author.isBlank(),
            colors = fieldColors, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Box {
            OutlinedTextField(
                value = formatLabel(format), onValueChange = {},
                label = { Text(stringResource(R.string.field_format)) }, readOnly = true, singleLine = true,
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )
            Box(
                Modifier
                    .matchParentSize()
                    .clickable(role = Role.DropdownList, onClickLabel = stringResource(R.string.field_format)) { formatMenu = true }
            )
            DropdownMenu(expanded = formatMenu, onDismissRequest = { formatMenu = false }, containerColor = Panel2) {
                MediaGroup.entries.forEach { g ->
                    Text(
                        stringResource(Words.group(g)), color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).semantics { heading() }
                    )
                    Formats.inGroup(g).forEach { f ->
                        DropdownMenuItem(text = { Text(formatLabel(f.id), color = Ink) }, onClick = { format = f.id; formatMenu = false })
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = yearText,
                onValueChange = { yearText = digits(it, 4) },
                label = { Text(stringResource(R.string.field_year)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors, modifier = Modifier.weight(1f)
            )
            val totalLabel = Words.totalField(info.unit)
            if (totalLabel != null) {
                OutlinedTextField(
                    value = pagesText,
                    onValueChange = { pagesText = digits(it, 5) },
                    label = { Text(stringResource(totalLabel)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = fieldColors, modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = partOfText,
            onValueChange = { text ->
                partOfText = text
                parentId = parentOptions.firstOrNull { it.title.trim().equals(text.trim(), ignoreCase = true) }?.id
            },
            label = { Text(stringResource(R.string.field_part_of)) },
            placeholder = { Text(stringResource(R.string.part_of_placeholder), color = Faint) },
            singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth()
        )
        // Suggestions from the library while typing; picking one links to it.
        val typed = partOfText.trim()
        val suggestions = remember(typed, parentOptions) {
            if (typed.isEmpty()) emptyList()
            else parentOptions.filter { it.title.contains(typed, ignoreCase = true) && !it.title.trim().equals(typed, ignoreCase = true) }.take(5)
        }
        suggestions.forEach { p ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = stringResource(R.string.use_this)) {
                        partOfText = p.title
                        parentId = p.id
                        if (author.isBlank()) author = p.author
                    }
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.title, color = Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(formatLabel(p.format), p.author).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Faint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (typed.isNotEmpty() && parent == null) {
            Text(
                stringResource(
                    if (suggestions.isEmpty()) R.string.part_of_new else R.string.part_of_pick,
                    formatLabel(Formats.parentFor(format)).lowercase()
                ),
                color = Faint, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (typed.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = positionText,
                onValueChange = { positionText = digits(it, 4) },
                label = { Text(stringResource(R.string.field_position)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = fieldColors, modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.cover_image), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        val inherited = parent?.let { data.inheritedCover(it) }
        val preview = Work(id = formId, title = title, author = author, imagePath = imagePath ?: inherited)
        val previewShape = coverShape(data, preview)
        Box(
            Modifier
                .height(160.dp)
                .width(160.dp * previewShape.aspect)
                .clip(RoundedCornerShape(6.dp))
        ) {
            CoverArt(Work(id = formId, title = title, author = author, imagePath = imagePath), Modifier.fillMaxSize(), fallbackPath = inherited)
        }
        if (imagePath == null && inherited != null) {
            Text(stringResource(R.string.using_collection_cover), color = Faint, fontSize = 12.sp)
        }
        Row {
            TextButton(onClick = { showLookup = true }) { Text(stringResource(R.string.look_up_online), color = Amber) }
            TextButton(onClick = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(stringResource(R.string.own_image), color = Amber) }
            if (imagePath != null) {
                TextButton(onClick = {
                    val prev = sessionImage
                    if (prev != null && prev != originalImage) store.deleteCover(prev)
                    sessionImage = null
                    imagePath = null
                }) { Text(stringResource(R.string.remove), color = Danger) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.put_it_in), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        val destOptions = listOf(
            States.TO_READ to stringResource(Words.state(States.TO_READ, group)),
            States.CURRENTLY to stringResource(Words.state(States.CURRENTLY, group)),
            States.PAUSED to stringResource(Words.state(States.PAUSED, group)),
            States.DNF to stringResource(Words.state(States.DNF, group)),
            States.READ to stringResource(R.string.log_as_finished)
        )
        destOptions.forEach { (value, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.RadioButton) { destState = value }
                    .padding(vertical = 2.dp)
            ) {
                RadioButton(
                    selected = destState == value,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = Amber, unselectedColor = Faint)
                )
                Spacer(Modifier.width(8.dp))
                Text(label, color = Ink, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
        if (showError && (title.isBlank() || (authorRequired && author.isBlank()))) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.title_author_required), color = Danger, fontSize = 14.sp)
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = {
                if (title.isBlank() || (authorRequired && author.isBlank())) {
                    showError = true
                    return@Button
                }
                val dup = store.data.findDuplicate(title, author, excludeId = existing?.id)
                if (existing == null && dup != null) duplicate = dup else doSave()
            },
            colors = primaryButtonColors(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.save), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- Log editor ----------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LogEditor(
    store: NovaloreStore,
    work: Work,
    logId: String?,
    onDirty: (Boolean) -> Unit,
    onDone: () -> Unit
) {
    val existing = remember(logId) { logId?.let { id -> store.data.logs.firstOrNull { it.id == id } } }
    val parent = store.data.parentOf(work)
    val group = Formats.groupOf(work.format)
    // Stories inside a book are "read in" that book; everything else says where it was watched or played.
    val readInFromParent = parent != null && group == MediaGroup.READ
    val initDate = existing?.finishedDate?.ifBlank { null } ?: LocalDate.now().toString()
    val initReadIn = existing?.readIn ?: ""
    val initVerdict = existing?.verdict ?: ""
    val initReview = existing?.review ?: ""
    val initRating = existing?.rating
    val initDnf = existing?.dnf ?: (work.state == States.DNF)

    var dateStr by rememberSaveable { mutableStateOf(initDate) }
    var readIn by rememberSaveable { mutableStateOf(initReadIn) }
    var verdict by rememberSaveable { mutableStateOf(initVerdict) }
    var review by rememberSaveable { mutableStateOf(initReview) }
    var rating by rememberSaveable { mutableStateOf(initRating) }
    var dnf by rememberSaveable { mutableStateOf(initDnf) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val dirty = dateStr != initDate || readIn != initReadIn || verdict != initVerdict || review != initReview ||
        rating != initRating || dnf != initDnf
    LaunchedEffect(dirty) { onDirty(dirty) }
    DisposableEffect(Unit) { onDispose { onDirty(false) } }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = try {
                LocalDate.parse(dateStr).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            colors = DatePickerDefaults.colors(containerColor = Panel),
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { ms ->
                        dateStr = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok), color = Amber) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel), color = Dim) }
            }
        ) {
            DatePicker(state = pickerState, title = null, headline = null, showModeToggle = false)
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Panel,
            title = { Text(stringResource(R.string.delete_log_title), color = Ink) },
            text = { Text(stringResource(R.string.delete_log_body, fmtDate(existing.finishedDate)), color = Dim) },
            confirmButton = {
                TextButton(onClick = {
                    store.update { d ->
                        d.logs.removeAll { it.id == existing.id }
                        val w = d.work(work.id)
                        if (w != null && (w.state == States.READ || w.state == States.DNF) && d.logs.none { it.workId == work.id }) {
                            w.state = States.TO_READ
                        } else {
                            d.syncStateWithLatestLog(work.id)
                        }
                    }
                    confirmDelete = false
                    onDone()
                }) { Text(stringResource(R.string.delete), color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel), color = Dim) } }
        )
    }

    val templates = listOf(
        stringResource(R.string.template_story_notes) to stringArrayResource(R.array.template_story_notes).toList(),
        stringResource(R.string.template_pros_cons) to stringArrayResource(R.array.template_pros_cons).toList(),
        stringResource(R.string.template_spoiler_free) to stringArrayResource(R.array.template_spoiler_free).toList()
    )

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Text(work.title, color = Faint, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (dnf) R.string.stopped_label else Words.finishedLabel(group)), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = { showDatePicker = true }) {
                Text(fmtDate(dateStr), color = Amber)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { dnf = !dnf }
        ) {
            Checkbox(checked = dnf, onCheckedChange = null, colors = CheckboxDefaults.colors(checkedColor = AmberFill, checkmarkColor = OnAmber))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Words.didntFinishCheckbox(group)), color = Ink, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
        }
        Spacer(Modifier.height(12.dp))
        if (readInFromParent && parent != null) {
            Text(stringResource(R.string.read_in_value, parent.title), color = Dim, fontSize = 15.sp)
        } else {
            OutlinedTextField(
                value = readIn, onValueChange = { readIn = it },
                label = { Text(stringResource(Words.whereField(group))) },
                singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = verdict, onValueChange = { verdict = it },
            label = { Text(stringResource(R.string.field_verdict)) },
            singleLine = true, colors = fieldColors, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = review, onValueChange = { review = it },
            label = { Text(stringResource(R.string.field_review)) },
            minLines = 5, colors = fieldColors, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.add_prompts), color = Faint, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            templates.forEach { (name, prompts) ->
                AssistChip(
                    onClick = { review = ReviewTemplates.apply(review, prompts) },
                    label = { Text(name, fontSize = 13.sp) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = Panel, labelColor = Ink)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.rating_label), color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        RatingInput(rating) { rating = it }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = {
                store.update { d ->
                    if (existing == null) {
                        d.logs.add(
                            ReadingLog(
                                workId = work.id, finishedDate = dateStr,
                                readIn = if (readInFromParent) "" else readIn.trim(),
                                verdict = verdict.trim(), review = review.trim(), rating = rating, dnf = dnf
                            )
                        )
                        d.work(work.id)?.let { w ->
                            w.state = if (dnf) States.DNF else States.READ
                            if (!dnf && w.pageCount != null) w.pagesRead = w.pageCount
                        }
                        // A back-dated read shouldn't override a later one when deciding finished vs. abandoned.
                        d.syncStateWithLatestLog(work.id)
                    } else {
                        d.logs.firstOrNull { it.id == existing.id }?.let { l ->
                            l.finishedDate = dateStr
                            if (!readInFromParent) l.readIn = readIn.trim()
                            l.verdict = verdict.trim()
                            l.review = review.trim()
                            l.rating = rating
                            l.dnf = dnf
                        }
                        d.syncStateWithLatestLog(work.id)
                    }
                }
                onDirty(false)
                onDone()
            },
            colors = primaryButtonColors(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.save), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        if (existing != null) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.delete_this_log), color = Danger)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------- Progress dialog ----------------

@Composable
fun ProgressDialog(work: Work, onDismiss: () -> Unit, onSave: (Int?, Int?) -> Unit) {
    val unit = Formats.of(work.format).unit
    val percent = unit == ProgressUnit.PERCENT
    var readText by rememberSaveable { mutableStateOf(work.pagesRead?.toString() ?: "") }
    var totalText by rememberSaveable { mutableStateOf(work.pageCount?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text(stringResource(R.string.update_progress), color = Ink) },
        text = {
            Column {
                OutlinedTextField(
                    value = readText, onValueChange = { readText = digits(it, if (percent) 3 else 5) },
                    label = { Text(stringResource(Words.doneField(unit))) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = fieldColors, modifier = Modifier.fillMaxWidth()
                )
                if (!percent) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = totalText, onValueChange = { totalText = digits(it, 5) },
                        label = { Text(stringResource(R.string.field_total_pages)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = fieldColors, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (percent) onSave(readText.toIntOrNull()?.coerceIn(0, 100), null)
                else onSave(readText.toIntOrNull(), totalText.toIntOrNull()?.takeIf { it > 0 })
            }) {
                Text(stringResource(R.string.save), color = Amber)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Dim) } }
    )
}

// ---------------- Online lookup dialog ----------------

@Composable
fun LookupDialog(
    initialTitle: String,
    initialAuthor: String,
    format: String,
    onDismiss: () -> Unit,
    onPick: (LookupCandidate) -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var author by rememberSaveable { mutableStateOf(initialAuthor) }
    var searching by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<LookupOutcome?>(null) }
    val scope = rememberCoroutineScope()

    fun runSearch() {
        if (title.isBlank()) return
        searching = true
        scope.launch(Dispatchers.IO) {
            val found = searchOnline(title.trim(), author.trim(), format)
            withContext(Dispatchers.Main) {
                outcome = found
                searching = false
            }
        }
    }

    LaunchedEffect(Unit) { runSearch() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.look_up_online), color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text(stringResource(R.string.field_title_plain)) }, singleLine = true,
                    colors = fieldColors, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = author, onValueChange = { author = it },
                    label = { Text(stringResource(Words.creator(Formats.of(format).creator, required = false))) }, singleLine = true,
                    colors = fieldColors, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(onClick = { runSearch() }, colors = primaryButtonColors()) { Text(stringResource(R.string.search)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Dim) }
                }
                Spacer(Modifier.height(8.dp))
                val o = outcome
                when {
                    searching -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Amber)
                    }
                    o != null && o.failed -> Text(stringResource(R.string.lookup_offline), color = Faint, fontSize = 14.sp)
                    o != null && o.results.isEmpty() -> Text(stringResource(R.string.lookup_no_results), color = Faint, fontSize = 14.sp)
                    o != null -> LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(o.results) { cand ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Panel2),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().clickable { onPick(cand) }
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RemoteThumb(cand.thumbUrl, 60.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(cand.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(cand.author, color = Dim, fontSize = 13.sp)
                                        val meta = listOfNotNull(cand.year?.toString(), cand.pageCount?.let { lengthLabel(Formats.of(format).unit, it) })
                                        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), color = Faint, fontSize = 12.sp)
                                        if (cand.coverUrl == null) Text(stringResource(R.string.no_cover_image), color = Faint, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.lookup_footer), color = Faint, fontSize = 12.sp)
            }
        }
    }
}
