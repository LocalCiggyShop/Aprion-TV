package org.privateview.quietscreen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import org.privateview.quietscreen.core.SearchSort
import org.privateview.quietscreen.updates.UpdateStatus

@Composable
private fun ChoiceDialog(title: String, description: String, actions: List<Pair<String, () -> Unit>>, close: () -> Unit) {
    val focus = remember(actions.size) { List(actions.size) { FocusRequester() } }
    Dialog(onDismissRequest = close) {
        Column(Modifier.widthIn(max = 460.dp).background(Panel, RoundedCornerShape(16.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, color = Cloud, fontSize = 23.sp)
            Text(description, color = Muted, fontSize = 16.sp)
            actions.forEachIndexed { index, (label, action) ->
                TvAction(label, Modifier.fillMaxWidth().focusRequester(focus[index]).onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) when (event.key) {
                        Key.DirectionUp, Key.DirectionLeft -> { focus[Math.floorMod(index - 1, actions.size)].requestFocus(); true }
                        Key.DirectionDown, Key.DirectionRight -> { focus[(index + 1) % actions.size].requestFocus(); true }
                        else -> false
                    } else false
                }, onClick = action)
            }
        }
        LaunchedEffect(Unit) { repeat(2) { withFrameNanos { } }; focus.first().requestFocus() }
    }
}

@Composable
internal fun UpdateInstallDialog(status: UpdateStatus, close: () -> Unit, install: () -> Unit) = ChoiceDialog(
    "Update ready to install", status.message + "\n\nInstall now to finish updating. Android may ask for permission or confirmation.",
    listOf("Install update" to install, "Later" to close), close)

@Composable
internal fun SearchSortDialog(sort: SearchSort, close: () -> Unit, select: (SearchSort) -> Unit) = ChoiceDialog(
    "Sort search results", "Newest first orders loaded results by upload date. More results expands the list. Relevance keeps YouTube's closest matches first.",
    SearchSort.entries.map { (it.label + if (it == sort) " ✓" else "") to { select(it) } } + ("Cancel" to close), close)
