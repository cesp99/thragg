package to.eyed.seeker.code.ui.shell.licences

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.BuildConfig
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.components.CopyChip
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.SectionHeader
import to.eyed.seeker.code.ui.components.SeekerCard
import to.eyed.seeker.code.ui.components.SeekerSearchField
import to.eyed.seeker.code.ui.shell.Route
import to.eyed.seeker.code.ui.shell.ShellState
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.MonoSmall
import to.eyed.seeker.code.ui.theme.RowChevron

/**
 * Open source licences — the list half, and the one screen in this app whose
 * job is legal rather than useful.
 *
 * docs/LICENSING.md §5 specifies it, and the reason it exists is stated there
 * without much charity: the APK already ships licence text that no screen can
 * reach, and "bytes in an APK that no screen can reach" do not satisfy MIT's
 * or ISC's requirement that the notice appear in all copies. So the test this
 * screen has to pass is narrow and concrete — *a reader can find any
 * component and read its actual licence, offline* — and everything on it is
 * in service of that.
 *
 * Four things are here that a normal list screen would not have:
 *
 *  1. **The four Appropriate Legal Notices**, at the top, verbatim. GPLv3 s0
 *     defines them and s5(d) makes displaying them a *condition* for an
 *     interactive program; before this screen the app displayed none of the
 *     four anywhere. They are string resources so they translate.
 *  2. **The source URL**, from BuildConfig, which reads `core/Cargo.toml`, so
 *     the one URL §3 requires everything to agree on cannot drift into a
 *     Kotlin constant. It is drawn in [MonoSmall] and carries a [CopyChip],
 *     because a URL is an identifier and the reviewer this screen is written
 *     for is going to want it in a clipboard rather than in a photograph.
 *  3. **The written offer**, in full. It takes the second of the two forms
 *     GPLv3 §6(b) permits — access to copy the source from a network server
 *     at no charge — so there is no postal address to show, and its absence
 *     is deliberate rather than a blank (docs/LICENSING.md §3).
 *  4. **Selectable text** over both of those, because the reviewer this
 *     screen is written for will want to copy them.
 *
 * Everything below the header comes from `assets/licenses/components.json`
 * ([Licences]), which is generated. Adding a dependency and forgetting its
 * notice fails `./gradlew :app:verifyLicenceAssets` rather than quietly
 * producing a screen that is missing a row.
 *
 * The Material pass is mechanical and deliberately so (docs/VISUAL.md,
 * "Licences"): the shared [SectionHeader] per licence family, [SeekerCard]
 * groups of rows with a [HairlineDivider] between, the shared filter pill, and
 * the licence text left as Material prose — it is prose, and setting it in the
 * buffer face at 13sp would be both harder to read and a category error about
 * what a code island is.
 */
@Composable
fun LicencesScreen(state: ShellState, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Blocking, once: 260 KB of JSON parsed off the main thread, then a field
    // read for the life of the process ([Licences.catalog]). Null while it is
    // in flight, which is one frame in practice and is drawn as such rather
    // than as an empty list — an empty licences screen means something.
    val catalog by produceState<LicenceCatalog?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Licences.catalog(context) }
    }

    var query by remember { mutableStateOf("") }
    val shown = remember(catalog, query) { catalog?.filter(query) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = MD.space4),
    ) {
        item(key = "header") { LegalNotices(state) }

        if (catalog == null) {
            item(key = "loading") {
                Note(stringResource(R.string.licences_loading))
            }
            return@LazyColumn
        }
        if (catalog?.isEmpty == true) {
            item(key = "unavailable") {
                Note(stringResource(R.string.licences_unavailable), error = true)
            }
            return@LazyColumn
        }

        item(key = "filter") {
            Column(modifier = Modifier.padding(top = MD.space4)) {
                // The shell's one field shape, borrowed rather than redrawn —
                // it is the same pill as the composer and the model drill, and
                // a user who has typed into one knows what this is. No
                // autofocus: arriving at a compliance screen with the keyboard
                // up hides the notices the screen exists to show.
                SeekerSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.licences_search_placeholder),
                )
                val total = catalog?.components?.size ?: 0
                val matching = shown?.components?.size ?: 0
                Text(
                    text = if (query.isBlank()) {
                        stringResource(R.string.licences_count, total)
                    } else {
                        stringResource(R.string.licences_count_filtered, matching, total)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MD.space2, start = MD.space1),
                )
            }
        }

        if (shown?.isEmpty == true) {
            item(key = "no-matches") {
                Note(stringResource(R.string.licences_no_matches))
            }
        }

        for (group in shown?.groups.orEmpty()) {
            item(key = "group-${group.id}") {
                GroupHeader(title = group.title, note = group.note)
            }
            // Keyed on the component id, which the generator guarantees is
            // unique across the whole file — 615 rows re-keyed on every
            // keystroke of the filter otherwise costs a full relayout. One
            // card per row rather than one per group, for the same reason the
            // rows are lazy at all: a group here can be six hundred long, and
            // a group-shaped card would be one item six hundred rows tall.
            itemsIndexed(group.components, key = { _, it -> it.id }) { index, component ->
                SeekerCard(
                    modifier = Modifier.fillMaxWidth(),
                    // The group is one card drawn one row at a time: the
                    // corners are rounded only at its two ends, so six hundred
                    // lazy items still read as a single block. A group-shaped
                    // card would have to be one item six hundred rows tall,
                    // which is the whole cost of laziness handed back.
                    shape = groupShape(index, group.components.size),
                    onClick = {
                        state.push(Route.LicenceDetail(component.id, component.name))
                    },
                ) {
                    if (index > 0) HairlineDivider()
                    ComponentRow(component)
                }
            }
        }

        item(key = "trademarks") {
            // The list closes with the trademark position, per §5 and
            // docs/TRADEMARKS.md. It belongs at the end rather than in the
            // header: it is about the ~64 file-type logos, which are rows in
            // the group above it, not about the app's own licence.
            Column(modifier = Modifier.padding(top = MD.space6, bottom = MD.space8)) {
                HairlineDivider()
                Text(
                    text = stringResource(R.string.licences_trademarks),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = MD.space4),
                )
            }
        }
    }
}

/**
 * The header block: the four notices, the source, and the written offer.
 *
 * Pulled out as its own composable because it is the part a lawyer reads and
 * the part that must not be edited casually — every paragraph in it is
 * discharging a specific clause, and the string resources say which.
 */
@Composable
private fun LegalNotices(state: ShellState) {
    val scheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth().padding(top = MD.space4)) {
        // GPLv3 s0/s5(d): the copyright notice, the warranty disclaimer, the
        // redistribution statement, and a way to see the licence itself.
        SelectionContainer {
            Column {
                Text(
                    text = stringResource(R.string.licences_notice_copyright),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.licences_notice_warranty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.padding(top = MD.space2),
                )
                Text(
                    text = stringResource(R.string.licences_notice_redistribute),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurface,
                    modifier = Modifier.padding(top = MD.space1),
                )
            }
        }
        // The fourth notice is a way to *see* the licence, which on a phone
        // means a row that opens it rather than 35 KB of text inline. The
        // GPL is a row in the list below as well; this is the one the notice
        // itself points at, so it is here.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MD.space3),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MD.space2)
                .clickable {
                    state.push(
                        Route.LicenceDetail(APP_COMPONENT_ID, APP_COMPONENT_NAME)
                    )
                }
                .heightIn(min = MD.rowMin),
        ) {
            Text(
                text = stringResource(R.string.licences_notice_view_gpl),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.primary,
                modifier = Modifier.weight(1f),
            )
            RowChevron()
        }

        Text(
            text = stringResource(R.string.licences_version_note),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MD.space1),
        )

        SectionHeader(
            stringResource(R.string.licences_source_heading),
            modifier = Modifier.padding(top = MD.space6),
        )
        Text(
            text = stringResource(R.string.licences_source_intro),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        SeekerCard(modifier = Modifier.fillMaxWidth().padding(top = MD.space2)) {
            SelectionContainer {
                Column(modifier = Modifier.padding(MD.space3)) {
                    Text(
                        text = BuildConfig.SOURCE_URL,
                        style = MonoSmall,
                        color = scheme.onSurface,
                    )
                    Text(
                        // The release, and the upstream commit the engine was
                        // vendored at — the two identifiers that make "the
                        // source for this build" a resolvable claim rather
                        // than a gesture at a repository.
                        text = stringResource(
                            R.string.licences_source_release,
                            BuildConfig.VERSION_NAME,
                            BuildConfig.ZED_COMMIT,
                        ),
                        style = MonoSmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = MD.space05),
                    )
                }
            }
            // The whole block in one copy: a reviewer resolving the offer
            // needs the URL *and* the release it is a claim about, and two
            // separate selections is two chances to paste half of one. It
            // replaces nothing — there was no way to copy this before.
            Row(modifier = Modifier.padding(start = MD.space3, bottom = MD.space3)) {
                CopyChip(
                    text = BuildConfig.SOURCE_URL + "\n" +
                        BuildConfig.VERSION_NAME + "\n" + BuildConfig.ZED_COMMIT,
                    label = "Copy",
                )
            }
        }

        SectionHeader(
            stringResource(R.string.licences_offer_heading),
            modifier = Modifier.padding(top = MD.space6),
        )
        SelectionContainer {
            Column {
                Text(
                    text = stringResource(R.string.licences_offer_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.licences_offer_address),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurface,
                    modifier = Modifier.padding(top = MD.rowPadY),
                )
            }
        }
        Text(
            text = stringResource(R.string.licences_offer_note),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = MD.rowPadY),
        )
    }
}

/**
 * The app's own row, which the "View the GPL v3" link opens.
 *
 * Hard-coded here and nowhere else, and it is an *identifier*, not content:
 * the row it names, with its copyright, its offer and the GPL text under it,
 * comes from the manifest like every other row. The generator fails if the id
 * is not unique, so a rename that broke this link would break the build.
 */
private const val APP_COMPONENT_ID = "app/seeker-ide"
private const val APP_COMPONENT_NAME = "Seeker IDE"

/** A group heading and the sentence that says what is, and is not, in it. */
@Composable
private fun GroupHeader(title: String, note: String?) {
    Column(modifier = Modifier.padding(top = MD.space6, bottom = MD.space1)) {
        SectionHeader(title)
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = MD.space2),
            )
        }
    }
}

/**
 * One component: "name · version · SPDX id", which is §5's row exactly.
 *
 * The name gets the one line that can ellipsize and the SPDX id never does —
 * on a 400dp column something has to give, and "MIT OR Apache-2.0" truncated
 * to "MIT OR Apac…" is the half of the row that stops being an answer. The
 * version and the id are identifiers, so they are set in the buffer face.
 */
@Composable
private fun ComponentRow(component: LicenceComponent) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MD.space3),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MD.rowMin)
            .padding(horizontal = MD.space3, vertical = MD.space2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = component.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    component.version.takeIf { it.isNotBlank() },
                    component.spdx,
                ).joinToString(" · "),
                style = MonoSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MD.space05),
            )
        }
        RowChevron()
    }
}

/**
 * The corner radii of one row inside a card group: rounded at the ends of the
 * run and square in the middle, so a lazy list of rows reads as one card.
 *
 * A single row group is rounded on all four corners, which is why the two
 * conditions are asked separately rather than as a `when` over the position.
 */
private fun groupShape(index: Int, count: Int): Shape = RoundedCornerShape(
    topStart = if (index == 0) MD.radiusMd else 0.dp,
    topEnd = if (index == 0) MD.radiusMd else 0.dp,
    bottomStart = if (index == count - 1) MD.radiusMd else 0.dp,
    bottomEnd = if (index == count - 1) MD.radiusMd else 0.dp,
)

/** A paragraph that is the whole of what the screen has to say right now. */
@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(vertical = MD.space3),
    )
}
