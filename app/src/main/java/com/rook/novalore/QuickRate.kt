package com.rook.novalore

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The quick way to rate an episode, chapter, season or story: stars and an optional one-line verdict, saved in one
 * tap. "Write a full review" saves too, then opens the full review editor for the rest.
 */
@Composable
fun QuickRateDialog(
    vm: NovaloreViewModel,
    workId: String,
    onDismiss: () -> Unit,
    onFullReview: (workId: String, logId: String?) -> Unit
) {
    val data = vm.store.data
    val work = data.work(workId)
    if (work == null) {
        LaunchedEffect(workId) { onDismiss() }
        return
    }
    val latest = data.latestLog(workId)
    var rating by rememberSaveable(workId) { mutableStateOf(latest?.rating) }
    var verdict by rememberSaveable(workId) { mutableStateOf(latest?.verdict ?: "") }
    val where = data.parentOf(work)?.let { p -> displayTitle(data, p) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = {
            Column {
                Text(numberedTitle(work), color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (where != null) Text(where, color = Faint, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column {
                RatingInput(rating) { rating = it }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = verdict, onValueChange = { verdict = it },
                    label = { Text(stringResource(R.string.field_verdict)) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Ink, unfocusedTextColor = Ink, focusedBorderColor = Amber, unfocusedBorderColor = Panel2,
                        focusedLabelColor = Amber, unfocusedLabelColor = Faint, cursorColor = Amber
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = {
                    val logId = vm.quickRate(workId, rating, verdict)
                    onDismiss()
                    onFullReview(workId, logId)
                }) { Text(stringResource(R.string.write_full_review), color = Amber) }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.quickRate(workId, rating, verdict); onDismiss() }) {
                Text(stringResource(R.string.save), color = Amber)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Dim) } }
    )
}
