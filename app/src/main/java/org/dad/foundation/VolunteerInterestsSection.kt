package org.dad.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp

/**
 * The free-form event interests a volunteer picks (E-1), shown under the volunteer opt-in. Each interest is a row with
 * its own "Remove" button, so TalkBack reads "Remove <interest>" and every target is at least 48dp.
 */
@Composable
internal fun VolunteerInterestsSection(model: SettingsModel, interests: List<String>) {
    var draft by rememberSaveable { mutableStateOf("") }
    val add = { if (model.addInterest(draft)) draft = "" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("interests-section")) {
        Text("Volunteer interests", style = MaterialTheme.typography.titleSmall, modifier = Modifier.dadHeading())
        Text("Tell us what you’d like to help with, such as “Park cleanups”.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = draft, onValueChange = { draft = it }, singleLine = true,
            label = { Text("Add an interest") },
            supportingText = { Text("${interests.size} of ${VolunteerInterests.MAX_COUNT} added") },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.fillMaxWidth().testTag("interest-input"),
        )
        DadSecondaryButton("Add interest", Modifier.testTag("interest-add"), enabled = draft.isNotBlank(), onClick = add)
        model.interestNotice?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("interest-notice").semantics { liveRegion = LiveRegionMode.Polite })
        }
        interests.forEach { interest ->
            Row(Modifier.fillMaxWidth().testTag("interest-row-$interest"), verticalAlignment = Alignment.CenterVertically) {
                Text(interest, modifier = Modifier.weight(1f))
                DadTextButton("Remove", Modifier.testTag("interest-remove-$interest").semantics { contentDescription = "Remove $interest" }) {
                    model.removeInterest(interest)
                }
            }
        }
    }
}
