package to.eyed.seeker.code.ui.shell.licences

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.seeker.code.R
import to.eyed.seeker.code.ui.components.HairlineDivider
import to.eyed.seeker.code.ui.components.SectionHeader
import to.eyed.seeker.code.ui.components.SeekerCard
import to.eyed.seeker.code.ui.theme.MD
import to.eyed.seeker.code.ui.theme.MonoSmall

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
 *
 * THE LICENCE TEXT IS NOT A CODE BLOCK, and that is a decision rather than an
 * omission (docs/VISUAL.md, "Licences"). It is prose — long, hard-wrapped,
 * legal prose — and setting it in the buffer face at 13sp would be harder to
 * read *and* a category error about what a Zed island is for. What does go in
 * the buffer face is the identifiers: the version, the SPDX expression and the
 * upstream URL, which are things you copy rather than things you read.
 */
@Composable
fun LicenceDetailScreen(
    componentId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = MD.space4),
    ) {
        if (loaded == null) {
            item { Paragraph(stringResource(R.string.licences_loading), scheme.onSurfaceVariant) }
            return@LazyColumn
        }
        if (component == null) {
            item {
                Paragraph(stringResource(R.string.licences_unavailable), scheme.onSurfaceVariant)
            }
            return@LazyColumn
        }

        item(key = "identity") {
            // Selectable in one block: an OEM reviewer copying a row into a
            // spreadsheet wants the name, the version and the SPDX id
            // together, and three separate selections is three chances to
            // paste half of one.
            SeekerCard(modifier = Modifier.fillMaxWidth().padding(top = MD.space4)) {
                SelectionContainer {
                    Column(modifier = Modifier.fillMaxWidth().padding(MD.space3)) {
                        Text(
                            text = component.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                        )
                        Text(
                            text = listOfNotNull(
                                component.version.takeIf { it.isNotBlank() },
                                component.spdx,
                            ).joinToString(" · "),
                            style = MonoSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = MD.space05),
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
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = MD.space2),
                            )
                        }

                        component.url?.let { url ->
                            Field(
                                label = stringResource(R.string.licences_detail_upstream),
                                value = url,
                                mono = true,
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
        }

        component.note?.let { note ->
            item(key = "note") {
                Paragraph(note, scheme.onSurfaceVariant)
            }
        }

        // One block per licence text, in the order the row names them, which
        // is the order of the SPDX expression — so the primary licence of a
        // dual-licensed crate is the one you land on.
        for ((path, text) in texts) {
            item(key = "text-$path") {
                Column(modifier = Modifier.fillMaxWidth().padding(top = MD.space6)) {
                    HairlineDivider()
                    SectionHeader(
                        // The file name, because it is also the SPDX
                        // identifier and because a reviewer checking that we
                        // shipped the right text wants to know which one this
                        // is without reading a paragraph of it.
                        text = path.substringAfterLast('/').removeSuffix(".txt"),
                        modifier = Modifier.padding(top = MD.space4),
                    )
                    if (text == null) {
                        Paragraph(
                            stringResource(R.string.licences_detail_text_missing),
                            scheme.error,
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
                                color = scheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        item(key = "tail") {
            // The nav bar sits under this list; without the gap the last line
            // of the GPL's "How to Apply These Terms" ends underneath it.
            Spacer(modifier = Modifier.height(MD.space8))
        }
    }
}

/**
 * A labelled value in the identity block.
 *
 * [mono] for the values that are identifiers rather than sentences — an
 * upstream URL is copied, not read, and a proportional face is where a
 * hyphen and an underscore in a crate path stop being distinguishable.
 */
@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = MD.rowPadY),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = MD.space3),
        )
        Text(
            text = value,
            style = if (mono) MonoSmall else MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Paragraph(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(vertical = MD.space2),
    )
}
