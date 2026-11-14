package com.aicarcompanion.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aicarcompanion.app.ui.vehicles.Options

/** Case-insensitive filter that ranks prefix matches before other matches. */
fun <T> filterOptions(items: List<T>, query: String, label: (T) -> String): List<T> {
    val q = query.trim()
    if (q.isEmpty()) return items
    val matches = items.filter { label(it).contains(q, ignoreCase = true) }
    return matches.sortedBy { if (label(it).startsWith(q, ignoreCase = true)) 0 else 1 }
}

/**
 * A field that opens a full-screen, searchable list. Shows loading and error states inline
 * and stays disabled until the previous selection has been made.
 */
@Composable
fun <T> SearchableSelector(
    label: String,
    selected: T?,
    options: Options<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    disabledHint: String? = null,
    onRetry: (() -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    val canOpen = enabled && options is Options.Loaded && options.items.isNotEmpty()
    val valueText = selected?.let(itemLabel)
    val status = when {
        !enabled -> disabledHint ?: "Not available yet"
        options is Options.Loading -> "Loading…"
        options is Options.Error -> options.message
        options is Options.Loaded && options.items.isEmpty() -> "No options found"
        valueText == null -> "Tap to choose"
        else -> null
    }

    Column(modifier.fillMaxWidth()) {
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(enabled = canOpen, role = Role.Button, onClickLabel = "choose $label") { open = true }
                .semantics(mergeDescendants = true) {
                    contentDescription = "$label: ${valueText ?: status ?: "not selected"}"
                },
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        valueText ?: status.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = when {
                            options is Options.Error -> MaterialTheme.colorScheme.error
                            valueText == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                when {
                    enabled && options is Options.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    canOpen -> Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
            }
        }
        if (enabled && options is Options.Error && onRetry != null) {
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        if (enabled && options is Options.Loaded && options.fromCache) {
            Text(
                "Saved list (may be offline)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp),
            )
        }
    }

    if (open && options is Options.Loaded) {
        SelectorDialog(
            title = label,
            items = options.items,
            itemLabel = itemLabel,
            onDismiss = { open = false },
            onSelect = { open = false; onSelect(it) },
        )
    }
}

@Composable
private fun <T> SelectorDialog(
    title: String,
    items: List<T>,
    itemLabel: (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val filtered = remember(items, query) { filterOptions(items, query, itemLabel) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().imePadding()) {
            Column {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Text("Choose ${title.lowercase()}", style = MaterialTheme.typography.titleLarge)
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
                )
                Text(
                    "${filtered.size} of ${items.size}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Top) {
                    items(filtered) { item ->
                        Text(
                            itemLabel(item),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .clickable(role = Role.Button) { onSelect(item) }
                                .padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
