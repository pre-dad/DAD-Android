package org.dad.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The "Your insights" card on the donor home. Draws nothing when the layout resolves to no blocks. */
@Composable
internal fun InsightsSection(blocks: List<InsightBlock>, onNavigate: (AccountDestination) -> Unit) {
    if (blocks.isEmpty()) return
    HomeCard("insights") {
        Text(stringResource(R.string.insights_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading().testTag("insights-title"))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            blocks.forEachIndexed { index, block -> InsightBlockView(block, "insights-$index", onNavigate) }
        }
    }
}

@Composable
private fun InsightBlockView(block: InsightBlock, tag: String, onNavigate: (AccountDestination) -> Unit) {
    when (block) {
        is InsightBlock.Heading -> Text(block.text.asString(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.dadHeading().testTag("$tag-heading"))
        is InsightBlock.Body -> Text(block.text.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("$tag-text"))
        is InsightBlock.Metrics -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("$tag-metrics")) {
            block.cells.forEachIndexed { index, cell ->
                Column(Modifier.semantics(mergeDescendants = true) {}.testTag("$tag-metric-$index")) {
                    Text(cell.value.asString(), style = MaterialTheme.typography.headlineSmall)
                    cell.caption?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        is InsightBlock.Callout -> Column(
            Modifier.fillMaxWidth()
                .background(DadTheme.color(when (block.tone) { "success" -> "ok-wash"; "warning" -> "warn-wash"; else -> "brand-wash" }), RoundedCornerShape(DadTheme.radius("card")))
                .padding(16.dp).testTag("$tag-callout").semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(block.title, style = MaterialTheme.typography.titleSmall, color = DadTheme.color("ink"), modifier = Modifier.dadHeading())
            Text(block.body.asString(), style = MaterialTheme.typography.bodyMedium, color = DadTheme.color("ink"))
        }
        is InsightBlock.Rows -> Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("$tag-list")) {
            block.rows.forEachIndexed { index, row -> InsightBlockView(row, "$tag-row-$index", onNavigate) }
        }
        is InsightBlock.Link -> OutlinedButton(
            onClick = { onNavigate(block.destination) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-link"),
        ) { Text(block.label.asString()) }
    }
}
