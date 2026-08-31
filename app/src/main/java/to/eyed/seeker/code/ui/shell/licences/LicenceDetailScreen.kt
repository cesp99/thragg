package to.eyed.seeker.code.ui.shell.licences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.theme.LocalZedTheme

/**
 * One component, and its licence in full.
 *
 * §5: "Each detail screen: copyright holder, upstream URL, and the **full**
 * licence text — not a summary, not a link." That sentence is the whole
 * design. There is no "read more", no web view and no network call; the text
 * is an asset, and a reader with the radio off gets the same 35 KB of GPL as
 * anyone else.
 *
 * A row can carry more than one text and usually does — 196 of the crates are
 * "MIT OR Apache-2.0", and an LGPL row needs LGPLv3 *and* the GPLv3 it sits on
 * — so the texts are stacked with a heading each rather than merged. Reading
 * two licences and being told which is which is the point; a single scroll of
 * concatenated legalese is not.
 *
 * The header above them is the component's own notice: the copyright line
 * copied out of its licence file, or, where upstream shipped none inside the
 * package, its authors under a heading that says *authors* rather than
 * pretending they are a copyright notice.
 */
@Composable
fun LicenceDetailScreen(
    componentId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val theme = LocalZedTheme.current
    val muted = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant)
    val body = theme.color("text", MaterialTheme.colorScheme.onSurface)

    // The catalogue is already parsed by the time anyone can tap a row, so
    // this is a map lookup — but it is behind the same IO hop as the list
    // because a restored back stack can land straight here, with nothing
    // having read the asset yet.
    val loaded by produceState<Pair<LicenceComponent?, List<Pair<String, String?>>>?>(
        initialValue = null,
        key1 = componentId,
    ) {
        value = withContext(Dispatchers.IO) {
            val component = Licences.catalog(context)[componentId]
            component to component?.licenceFiles.orEmpty().map { path ->
                path to Licences.text(context, path)
            }
        }
    }

    val component = loaded?.first
    val texts = loaded?.second.orEmpty()

    LazyColumn(modifier = modifier.fillMaxSize()) {
        if (loaded == null) {
            item { Paragraph(stringResource(R.string.licences_loading), muted) }
            return@LazyColumn
        }
        if (component == null) {
            item { Paragraph(stringResource(R.string.licences_unavailable), muted) }
            return@LazyColumn
        }

        item(key = "identity") {
            // Selectable in one block: an OEM reviewer copying a row into a
            // spreadsheet wants the name, the version and the SPDX id
            // together, and three separate selections is three chances to
            // paste half of one.
            SelectionContainer {
                Column(modifier = Modifier.fillMaxWidth().padding(RowPadding)) {
                    Text(
                        text = component.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = body,
                    )
                    Text(
                        text = listOfNotNull(
                            component.version.takeIf { it.isNotBlank() },
                            component.spdx,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = muted,
                        modifier = Modifier.padding(top = 2.dp),
                    )

                    val copyright = component.copyright
                    if (copyright != null) {
                        Field(
                            label = stringResource(R.string.licences_detail_copyright),
                            value = copyright,
                        )
                    } else if (component.authors.isNotEmpty()) {
                        Field(
                            label = stringResource(R.string.licences_detail_authors),
                            value = component.authors.joinToString("\n"),
                        )
                        Text(
                            text = stringResource(R.string.licences_detail_no_copyright),
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }

                    component.url?.let { url ->
                        Field(
                            label = stringResource(R.string.licences_detail_upstream),
                            value = url,
                        )
                    }
                    component.origin?.let { origin ->
                        Field(
                            label = stringResource(R.string.licences_detail_origin),
                            value = origin,
                        )
                    }
                }
            }
        }

        component.note?.let { note ->
            item(key = "note") {
                Paragraph(note, muted)
            }
        }

        // One block per licence text, in the order the row names them, which
        // is the order of the SPDX expression — so the primary licence of a
        // dual-licensed crate is the one you land on.
        for ((path, text) in texts) {
            item(key = "text-$path") {
                Column(modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
                    HorizontalDivider(
                        color = theme.color(
                            "border",
                            MaterialTheme.colorScheme.outlineVariant,
                        )
                    )
                    Text(
                        // The file name, because it is also the SPDX
                        // identifier and because a reviewer checking that we
                        // shipped the right text wants to know which one this
                        // is without reading a paragraph of it.
                        text = path.substringAfterLast('/').removeSuffix(".txt"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = muted,
                        modifier = Modifier.padding(
                            start = RowPadding,
                            end = RowPadding,
                            top = 16.dp,
                            bottom = 8.dp,
                        ),
                    )
                    if (text == null) {
                        Paragraph(
                            stringResource(R.string.licences_detail_text_missing),
                            theme.color("error", MaterialTheme.colorScheme.error),
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = text,
                                // The licence is a document, not chrome:
                                // bodySmall keeps a hard-wrapped 74-column
                                // GPL paragraph on a 400dp column without
                                // re-wrapping every second line, and it is
                                // still above the platform's minimum
                                // legible size.
                                style = MaterialTheme.typography.bodySmall,
                                color = body,
                                modifier = Modifier.padding(horizontal = RowPadding),
                            )
                        }
                    }
                }
            }
        }

        item(key = "tail") {
            // The nav bar sits under this list; without the gap the last line
            // of the GPL's "How to Apply These Terms" ends underneath it.
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/** A labelled value in the identity block. */
@Composable
private fun Field(label: String, value: String) {
    val theme = LocalZedTheme.current
    Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text.muted", MaterialTheme.colorScheme.onSurfaceVariant),
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = theme.color("text", MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Paragraph(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(horizontal = RowPadding, vertical = 8.dp),
    )
}
