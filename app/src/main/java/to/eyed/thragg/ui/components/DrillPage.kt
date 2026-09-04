package to.eyed.thragg.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import to.eyed.thragg.R
import to.eyed.thragg.ui.theme.IconSize
import to.eyed.thragg.ui.theme.MD
import to.eyed.thragg.ui.theme.ThraggIcon
import to.eyed.thragg.ui.theme.ThraggIconButton
import to.eyed.thragg.ui.theme.mutedIcon
import to.eyed.thragg.ui.theme.revealItem

/**
 * A named run of options — one provider, one family, one settings group.
 *
 * [name] null draws the run with no header, which is what a flat list is: one
 * group, unnamed. That is why [DrillPage] takes groups rather than a list plus
 * an optional grouping — there is one code path, and an ungrouped page is the
 * degenerate case of it rather than a second branch.
 */
@Immutable
class Group<T>(val name: String?, val items: List<T>)

/**
 * The second page of a sheet: a long list, filtered, with the current value
 * already on screen.
 *
 * It exists because thirty models across five providers is not a list you can
 * scroll, and because Seeker's old answer was a SECOND `ModalBottomSheet`
 * opened on top of the first — two scrims, two drag handles and a back gesture
 * that means one thing to the user and two to the app. A drill page is content
 * the caller swaps inside the sheet it already has, so back unwinds one level
 * because there is one level.
 *
 * SEARCH SEMANTICS, which are the point of the field: A GROUP WHOSE NAME
 * MATCHES KEEPS ALL ITS OPTIONS. Users think "which Anthropic model am I on",
 * not "which model contains the substring I am typing", so typing "anthropic"
 * has to list that provider's models even though not one of their names
 * contains the word. Otherwise an item matches on whatever [searchText]
 * returns — its name, its wire value and its description, joined by the
 * caller, because only the caller knows what its own T is made of.
 *
 * IT OPENS SCROLLED TO THE CURRENT VALUE, through `revealItem`, so the setting
 * you are about to change is the one you are looking at — and through
 * `revealItem` specifically because that is the app's one scroll helper that
 * honours reduce-motion. This behaviour and the group headers are the two
 * things worth keeping from `ConfigSheets.kt`, and they are kept.
 *
 * [row] draws an item; [DrillRow] is the house style for one and every caller
 * that does not need something bespoke should hand it straight through. The
 * `Boolean` is whether the item is the current value.
 */
@Composable
fun <T> DrillPage(
    title: String,
    groups: List<Group<T>>,
    currentValue: String?,
    onSelect: (T) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    searchable: Boolean = true,
    valueOf: (T) -> String,
    searchText: (T) -> String = { valueOf(it) },
    row: @Composable (T, Boolean) -> Unit,
) {
    var query by rememberSaveable(title) { mutableStateOf("") }
    val needle = query.trim().lowercase(Locale.getDefault())
    val filtered = remember(groups, needle) {
        if (needle.isEmpty()) {
            groups
        } else {
            groups.mapNotNull { group ->
                // The provider-name match, and the reason it is checked first:
                // a group whose own name matches is entirely relevant, and
                // filtering its children by the same needle would empty it.
                if (group.name?.lowercase(Locale.getDefault())?.contains(needle) == true) {
                    group
                } else {
                    val kept = group.items.filter {
                        searchText(it).lowercase(Locale.getDefault()).contains(needle)
                    }
                    if (kept.isEmpty()) null else Group(group.name, kept)
                }
            }
        }
    }

    val listState = rememberLazyListState()
    // The index of the current value in the FLATTENED list, headers counted,
    // computed only while the list is unfiltered — once a query is typed, the
    // top of the results is the right place to be.
    LaunchedEffect(currentValue, groups) {
        if (needle.isNotEmpty() || currentValue == null) return@LaunchedEffect
        var index = 0
        for (group in groups) {
            if (group.name != null) index++
            val hit = group.items.indexOfFirst { valueOf(it) == currentValue }
            if (hit >= 0) {
                listState.revealItem(index + hit)
                return@LaunchedEffect
            }
            index += group.items.size
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = MD.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.space2),
        ) {
            ThraggIconButton(
                icon = R.drawable.ic_ui_arrow_left,
                description = "Back",
                onClick = onBack,
                tint = mutedIcon,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (searchable) {
            ThraggSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search ${title.lowercase(Locale.getDefault())}…",
                modifier = Modifier.padding(bottom = MD.space3),
            )
        }
        if (filtered.isEmpty()) {
            Text(
                text = "Nothing matches \"$query\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = MD.space4, vertical = MD.space6),
            )
            return@Column
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = MD.space6),
        ) {
            filtered.forEachIndexed { groupIndex, group ->
                if (group.name != null) {
                    item(key = "header-$groupIndex-${group.name}") {
                        SectionHeader(
                            text = group.name,
                            modifier = Modifier.padding(
                                top = if (groupIndex == 0) 0.dp else MD.space4,
                            ),
                        )
                    }
                }
                items(
                    count = group.items.size,
                    key = { "$groupIndex-${valueOf(group.items[it])}" },
                ) { itemIndex ->
                    val item = group.items[itemIndex]
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = valueOf(item) == currentValue,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(item) },
                                ),
                        ) {
                            row(item, valueOf(item) == currentValue)
                        }
                        HairlineDivider()
                    }
                }
            }
        }
    }
}

/**
 * The house row for a drill page: a name over a reason, and a check when it is
 * the one in force.
 *
 * A check rather than a radio, and only on the selected row. A column of empty
 * circles down a thirty-model list is thirty marks carrying one bit between
 * them; the check appears once and the eye finds it immediately. The row's
 * `selectable` semantics — supplied by [DrillPage] around this — still
 * announce "selected", so nothing is lost for a screen reader.
 *
 * 4dp × 10dp of padding on a 48dp minimum: dense, because these lists are
 * long, but never below the target floor.
 */
@Composable
fun DrillRow(
    name: String,
    description: String?,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MD.rowMin)
            .padding(horizontal = MD.space1, vertical = MD.rowPadY),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MD.space05),
                )
            }
        }
        if (selected) {
            ThraggIcon(
                icon = R.drawable.ic_ui_check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                size = IconSize.Inline,
            )
        }
    }
}
