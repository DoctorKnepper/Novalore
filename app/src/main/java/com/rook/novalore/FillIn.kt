package com.rook.novalore

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class FillStep { CHOOSE, SEARCH, PREVIEW, NUMBERED }

/**
 * "Fill in parts…": either looks a show up on TVmaze and adds its seasons and episodes, or adds numbered parts
 * (episodes, volumes, chapters, issues, stories) to anything. Each added part can be ticked off or reviewed on its own.
 */
@Composable
fun FillInDialog(vm: NovaloreViewModel, work: Work, onDismiss: () -> Unit) {
    val canLookUp = work.format == "TV show" || work.format == "Anime series" || work.format == "Season"
    var step by remember { mutableStateOf(if (canLookUp) FillStep.CHOOSE else FillStep.NUMBERED) }
    var plan by remember { mutableStateOf<List<PlannedPart>?>(null) }
    var planSource by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Panel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.fill_in_title, work.title), color = Ink, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                when (step) {
                    FillStep.CHOOSE -> {
                        Text(stringResource(R.string.fill_in_choose), color = Dim, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { step = FillStep.SEARCH }, colors = ButtonDefaults.buttonColors(containerColor = AmberFill, contentColor = OnAmber)) {
                            Text(stringResource(R.string.fill_in_tvmaze))
                        }
                        TextButton(onClick = { step = FillStep.NUMBERED }) { Text(stringResource(R.string.fill_in_numbered), color = Amber) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Dim) }
                    }
                    FillStep.SEARCH -> TvMazeSearch(
                        work,
                        onPlan = { p, name -> plan = p; planSource = name; step = FillStep.PREVIEW },
                        onCancel = onDismiss
                    )
                    FillStep.PREVIEW -> {
                        val p = plan.orEmpty()
                        val seasons = p.count { it.format == "Season" }
                        val episodes = p.countAll() - seasons
                        Text(stringResource(R.string.fill_in_preview, planSource), color = Ink, fontSize = 15.sp)
                        if (seasons > 0) Text(plural(R.plurals.seasons, seasons, seasons), color = Dim, fontSize = 14.sp)
                        Text(plural(R.plurals.episodes, episodes, episodes), color = Dim, fontSize = 14.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.fill_in_note), color = Faint, fontSize = 12.sp)
                        Row {
                            Button(
                                onClick = { vm.fillIn(work.id, p); onDismiss() },
                                enabled = p.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(containerColor = AmberFill, contentColor = OnAmber)
                            ) { Text(stringResource(R.string.fill_in_add)) }
                            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Dim) }
                        }
                    }
                    FillStep.NUMBERED -> NumberedParts(vm, work, onDone = onDismiss)
                }
            }
        }
    }
}

@Composable
private fun TvMazeSearch(work: Work, onPlan: (List<PlannedPart>, String) -> Unit, onCancel: () -> Unit) {
    // Searching from a season looks up the show it belongs to.
    var query by rememberSaveable { mutableStateOf(work.title) }
    var results by remember { mutableStateOf<List<LookupCandidate>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val seasonLabel = stringResource(R.string.format_season)

    fun search() {
        if (query.isBlank()) return
        busy = true
        scope.launch(Dispatchers.IO) {
            val found = searchTvMaze(query.trim())
            withContext(Dispatchers.Main) {
                results = found.orEmpty(); failed = found == null; busy = false
            }
        }
    }

    fun choose(c: LookupCandidate) {
        val id = c.tvmazeId ?: return
        busy = true
        scope.launch(Dispatchers.IO) {
            val p = fetchTvMazePlan(id, seasonLabel, flattenSingleSeason = work.format != "TV show")
            // From a season, only that season's episodes are added.
            val forWork = if (work.format == "Season" && p != null) {
                p.firstOrNull { it.position == work.position }?.children ?: p.flatMap { it.children }
            } else p
            withContext(Dispatchers.Main) {
                busy = false
                if (forWork == null) failed = true else onPlan(forWork, c.title)
            }
        }
    }

    LaunchedEffect(Unit) { search() }

    OutlinedTextField(
        value = query, onValueChange = { query = it }, singleLine = true,
        label = { Text(stringResource(R.string.field_title_plain)) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Amber, unfocusedBorderColor = Panel2,
            focusedLabelColor = Amber, unfocusedLabelColor = Faint, cursorColor = Amber
        ),
        modifier = Modifier.fillMaxWidth()
    )
    Row {
        TextButton(onClick = { search() }) { Text(stringResource(R.string.search), color = Amber) }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel), color = Dim) }
    }
    val r = results
    when {
        busy -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Amber) }
        failed -> Text(stringResource(R.string.lookup_offline), color = Faint, fontSize = 14.sp)
        r != null && r.isEmpty() -> Text(stringResource(R.string.fill_in_no_results), color = Faint, fontSize = 14.sp)
        r != null -> LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.6f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(r) { c ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Panel2),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable { choose(c) }
                ) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RemoteThumb(c.thumbUrl, 60.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(c.year?.toString(), c.author.ifBlank { null }).joinToString(" · "), color = Dim, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NumberedParts(vm: NovaloreViewModel, work: Work, onDone: () -> Unit) {
    val data = vm.store.data
    var format by rememberSaveable { mutableStateOf(Formats.childOf(work.format)) }
    val nextNumber = (data.childrenOf(work.id).filter { it.format == format }.mapNotNull { it.position }.maxOrNull() ?: 0) + 1
    // A known length (episodes or chapters from a lookup) suggests how many to add.
    val suggested = work.pageCount?.takeIf { it >= nextNumber && Formats.of(work.format).unit != ProgressUnit.PAGES }
    var fromText by rememberSaveable { mutableStateOf(nextNumber.toString()) }
    var toText by rememberSaveable { mutableStateOf(suggested?.toString() ?: "") }
    var menu by remember { mutableStateOf(false) }
    val label = formatLabel(format)
    val choices = listOf("Season", "Episode", "Volume", "Chapter", "Issue", "Short story", "DLC")
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Amber, unfocusedBorderColor = Panel2,
        focusedLabelColor = Amber, unfocusedLabelColor = Faint, cursorColor = Amber
    )

    Text(stringResource(R.string.fill_in_numbered_help), color = Dim, fontSize = 14.sp)
    Spacer(Modifier.height(8.dp))
    Box {
        TextButton(onClick = { menu = true }) { Text(stringResource(R.string.fill_in_kind, label), color = Amber) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Panel2) {
            choices.forEach { f -> DropdownMenuItem(text = { Text(formatLabel(f), color = Ink) }, onClick = { format = f; menu = false }) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = fromText, onValueChange = { fromText = it.filter(Char::isDigit).take(4) }, singleLine = true,
            label = { Text(stringResource(R.string.fill_in_from)) }, colors = colors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
        )
        OutlinedTextField(
            value = toText, onValueChange = { toText = it.filter(Char::isDigit).take(4) }, singleLine = true,
            label = { Text(stringResource(R.string.fill_in_to)) }, colors = colors,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f)
        )
    }
    val from = fromText.toIntOrNull() ?: 1
    val to = toText.toIntOrNull() ?: 0
    // Keeps one tap from creating an unmanageable list by mistake.
    val count = (to - from + 1).coerceAtLeast(0)
    val ok = count in 1..MAX_NUMBERED
    Spacer(Modifier.height(6.dp))
    Text(
        if (count > MAX_NUMBERED) stringResource(R.string.fill_in_too_many, MAX_NUMBERED)
        else if (ok) stringResource(R.string.fill_in_example, label, from, to) else "",
        color = Faint, fontSize = 12.sp
    )
    Row {
        Button(
            onClick = { vm.fillIn(work.id, numberedParts(format, label, from, to)); onDone() },
            enabled = ok,
            colors = ButtonDefaults.buttonColors(containerColor = AmberFill, contentColor = OnAmber)
        ) { Text(stringResource(R.string.fill_in_add)) }
        TextButton(onClick = onDone) { Text(stringResource(R.string.cancel), color = Dim) }
    }
}

private const val MAX_NUMBERED = 2000
