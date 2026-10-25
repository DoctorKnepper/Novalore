package com.rook.novalore

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings → Appearance: light/dark mode plus background, accent and text colour presets, applied live. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(current: Appearance, onChange: (Appearance) -> Unit) {
    val dark = current.isDark()
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        AppearancePreview()

        Spacer(Modifier.height(20.dp))
        SectionTitle(stringResource(R.string.appearance_mode))
        Spacer(Modifier.height(4.dp))
        listOf(
            ThemeMode.DARK to R.string.mode_dark,
            ThemeMode.LIGHT to R.string.mode_light,
            ThemeMode.SYSTEM to R.string.mode_system
        ).forEach { (mode, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = current.mode == mode, role = Role.RadioButton) { onChange(current.copy(mode = mode)) }
            ) {
                RadioButton(
                    selected = current.mode == mode,
                    onClick = null,
                    colors = RadioButtonDefaults.colors(selectedColor = Amber, unselectedColor = Faint)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(label), color = Ink, fontSize = 15.sp, modifier = Modifier.padding(vertical = 12.dp))
            }
        }

        // In "match phone" mode both backgrounds matter, so both pickers are shown.
        if (current.mode != ThemeMode.LIGHT) {
            Spacer(Modifier.height(16.dp))
            SectionTitle(stringResource(if (current.mode == ThemeMode.SYSTEM) R.string.appearance_dark_background else R.string.appearance_background))
            SwatchRow(
                items = Looks.darkBackgrounds,
                selectedId = Looks.background(current, dark = true).id,
                id = { it.id }, label = { it.label }, fill = { it.bg }, ring = { it.panel2 },
                onPick = { onChange(current.copy(darkBackground = it.id)) }
            )
        }
        if (current.mode != ThemeMode.DARK) {
            Spacer(Modifier.height(16.dp))
            SectionTitle(stringResource(if (current.mode == ThemeMode.SYSTEM) R.string.appearance_light_background else R.string.appearance_background))
            SwatchRow(
                items = Looks.lightBackgrounds,
                selectedId = Looks.background(current, dark = false).id,
                id = { it.id }, label = { it.label }, fill = { it.bg }, ring = { it.panel2 },
                onPick = { onChange(current.copy(lightBackground = it.id)) }
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.appearance_accent))
        SwatchRow(
            items = Looks.accents,
            selectedId = Looks.accent(current).id,
            id = { it.id }, label = { it.label }, fill = { if (dark) it.onDark else it.onLight }, ring = { Color.Transparent },
            onPick = { onChange(current.copy(accent = it.id)) }
        )

        Spacer(Modifier.height(16.dp))
        SectionTitle(stringResource(R.string.appearance_text))
        SwatchRow(
            items = Looks.texts,
            selectedId = Looks.text(current).id,
            id = { it.id }, label = { it.label }, fill = { if (dark) it.onDark.ink else it.onLight.ink }, ring = { Panel2 },
            onPick = { onChange(current.copy(text = it.id)) }
        )

        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.appearance_contrast_note), color = Faint, fontSize = 12.sp)
        if (current != Appearance()) {
            TextButton(onClick = { onChange(Appearance()) }) {
                Text(stringResource(R.string.appearance_reset), color = Amber)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A row of colour circles. The selected one gets an accent ring and a check mark; each reads its name for TalkBack. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> SwatchRow(
    items: List<T>,
    selectedId: String,
    id: (T) -> String,
    label: (T) -> Int,
    fill: @Composable (T) -> Color,
    ring: @Composable (T) -> Color,
    onPick: (T) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        items.forEach { item ->
            val selected = id(item) == selectedId
            val name = stringResource(label(item))
            val color = fill(item)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(64.dp)
                    .selectable(selected = selected, role = Role.RadioButton) { onPick(item) }
                    .semantics(mergeDescendants = true) { contentDescription = name }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(if (selected) 3.dp else 1.dp, if (selected) Amber else ring(item), CircleShape)
                ) {
                    if (selected) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = checkTint(color), modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    name, color = if (selected) Ink else Dim, fontSize = 12.sp, textAlign = TextAlign.Center,
                    maxLines = 2, modifier = Modifier.clearAndSetSemantics { }
                )
            }
        }
    }
}

/** Black or white, whichever stands out on the swatch. */
private fun checkTint(c: Color): Color = if (0.299 * c.red + 0.587 * c.green + 0.114 * c.blue > 0.5) Color.Black else Color.White

/** A small sample of the real UI so changes can be judged before leaving the screen. */
@Composable
private fun AppearancePreview() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { }
    ) {
        Row(Modifier.padding(12.dp)) {
            TitleCard(
                stringResource(R.string.preview_title), stringResource(R.string.preview_author),
                Modifier.width(58.dp).height(84.dp).clip(RoundedCornerShape(4.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.preview_title), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.preview_author), color = Dim, fontSize = 13.sp)
                Text(stringResource(R.string.preview_date), color = Faint, fontSize = 12.sp)
                Spacer(Modifier.height(3.dp))
                StarsRow(8.5, 12.dp)
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(containerColor = AmberFill, contentColor = OnAmber),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text(stringResource(R.string.finish_and_log), fontSize = 13.sp)
                }
            }
        }
    }
}
