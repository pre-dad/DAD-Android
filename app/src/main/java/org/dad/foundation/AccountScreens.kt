package org.dad.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Account management sections shown inside Settings (PRD §6.12, EM-2/EM-4/EM-5): contact details, the
// account email, the employer and team, and where the donor is signed in. Each section owns its own
// model, so one that cannot load says so without hiding the others.

private val lastUsedFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@Composable
private fun ErrorLine(message: UiText, tag: String) = ErrorLine(message.asString(), tag)

@Composable
private fun ErrorLine(message: String, tag: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
private fun StatusLine(message: UiText, tag: String) = StatusLine(message.asString(), tag)

@Composable
private fun StatusLine(message: String, tag: String) {
    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

/** ZIP, city and phone. The ZIP sets the donor's city for events and rankings, and their time zone. */
@Composable
internal fun ProfileDetailsSection(model: ProfileDetailsModel) {
    val busy = model.phase == DetailsPhase.Saving
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("details-section")) {
        Text(stringResource(R.string.acct_your_details), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Text(stringResource(R.string.acct_your_zip_code_sets_your_city_for), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = model.zip, onValueChange = { model.zip = it.take(10) }, label = { Text(stringResource(R.string.common_zip_code)) }, singleLine = true, enabled = !busy,
            isError = !model.zipValid, supportingText = if (model.zipValid) null else ({ Text(stringResource(R.string.acct_enter_a_5_digit_zip_code_or_leave)) }),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("details-zip"),
        )
        OutlinedTextField(
            value = model.city, onValueChange = { model.city = it.take(ProfileRules.CITY_LIMIT) }, label = { Text(stringResource(R.string.common_city)) }, singleLine = true, enabled = !busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("details-city"),
        )
        OutlinedTextField(
            value = model.phone, onValueChange = { model.phone = it.take(24) }, label = { Text(stringResource(R.string.acct_mobile_phone)) }, singleLine = true, enabled = !busy,
            isError = !model.phoneValid,
            supportingText = if (model.phoneValid) null else ({ Text(stringResource(R.string.acct_enter_a_us_number_or_include_the)) }),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("details-phone"),
        )
        when (val phase = model.phase) {
            DetailsPhase.Saved -> StatusLine(stringResource(R.string.acct_your_details_were_saved), "details-saved")
            is DetailsPhase.Failed -> ErrorLine(phase.message, "details-error")
            else -> Unit
        }
        OutlinedButton(onClick = model::save, enabled = model.canSave, modifier = Modifier.heightIn(min = 48.dp).testTag("details-save")) { Text(stringResource(R.string.acct_save_details)) }
    }
}

/**
 * Changing the account email: confirmation goes to the current address first, then the new one. Both links
 * open on the website, which completes the change and signs every device out.
 */
@Composable
internal fun EmailChangeSection(model: EmailChangeModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("email-change-section")) {
        when {
            !expanded -> OutlinedButton(onClick = { expanded = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("email-change-start")) {
                Text(stringResource(R.string.acct_change_account_email))
            }
            model.phase == EmailChangePhase.Sent -> StatusLine(stringResource(R.string.acct_email_change_sent), "email-change-sent")
            else -> {
                Text(stringResource(R.string.acct_change_account_email), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
                OutlinedTextField(
                    value = model.email, onValueChange = { model.email = it.take(254) }, label = { Text(stringResource(R.string.acct_new_email_address)) }, singleLine = true,
                    enabled = model.phase != EmailChangePhase.Submitting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("email-change-input"),
                )
                (model.phase as? EmailChangePhase.Failed)?.let { ErrorLine(it.message, "email-change-error") }
                DadPrimaryButton(stringResource(R.string.acct_send_confirmation), Modifier.testTag("email-change-submit")) { model.submit() }
                OutlinedButton(onClick = { model.edit(); expanded = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_cancel)) }
            }
        }
    }
}

/** Company code (EM-2), then the donor's team and the company team leaderboard (EM-4/EM-5). */
@Composable
internal fun EmployerSection(model: EmployerModel) {
    LaunchedEffect(Unit) { model.load() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("employer-section")) {
        Text(stringResource(R.string.acct_employer_and_team), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        when (val phase = model.phase) {
            LoadPhase.Loading -> CircularProgressIndicator(modifier = Modifier.contentDescribed(R.string.acct_loading_your_employer))
            is LoadPhase.Failed -> {
                ErrorLine(phase.message, "employer-load-error")
                OutlinedButton(onClick = model::load, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_try_again)) }
            }
            LoadPhase.Ready -> {
                val team = model.team
                val organization = team?.organization
                if (team != null && organization != null) Linked(model, organization, team) else {
                    Text(stringResource(R.string.acct_if_your_employer_gave_you_a), style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = model.companyCode, onValueChange = { model.companyCode = it.take(64) }, label = { Text(stringResource(R.string.common_company_code)) }, singleLine = true,
                        enabled = !model.busy,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().testTag("employer-code"),
                    )
                    OutlinedButton(onClick = model::enroll, enabled = model.canEnroll, modifier = Modifier.heightIn(min = 48.dp).testTag("employer-enroll")) {
                        Text(stringResource(R.string.acct_link_my_employer))
                    }
                }
            }
        }
        model.actionError?.let { ErrorLine(it, "employer-error") }
    }
}

@Composable
private fun Linked(model: EmployerModel, organization: String, team: EmployerTeam) {
    Text(stringResource(R.string.acct_linked_to, organization), modifier = Modifier.testTag("employer-linked"))
    val name = team.teamName
    if (name != null) {
        Text(stringResource(R.string.acct_your_team, name), modifier = Modifier.testTag("employer-team"))
    } else {
        OutlinedTextField(
            value = model.teamName, onValueChange = { model.teamName = it.take(80) }, label = { Text(stringResource(R.string.acct_team_name)) }, singleLine = true, enabled = !model.busy,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("employer-team-name"),
        )
        OutlinedButton(onClick = model::joinTeam, enabled = model.canJoinTeam, modifier = Modifier.heightIn(min = 48.dp).testTag("employer-join-team")) {
            Text(stringResource(R.string.acct_join_or_start_a_team))
        }
    }
    Text(stringResource(R.string.acct_team_leaderboard), style = MaterialTheme.typography.titleSmall)
    if (team.leaderboard.isEmpty()) {
        Text(stringResource(R.string.acct_teams_appear_here_once_they_have), style = MaterialTheme.typography.bodySmall)
    } else {
        team.leaderboard.forEachIndexed { index, row ->
            Column(Modifier.testTag("leaderboard-${row.id}")) {
                Text(stringResource(R.string.acct_leaderboard_rank_name, index + 1, row.name), style = MaterialTheme.typography.titleSmall)
                Text(pluralStringResource(R.plurals.acct_leaderboard_stats, row.activeGivers.toQuantity(), row.activeGivers, formatUsdMinor(row.annualizedMinor.clampToInt()), row.avgStreakDays),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Where the donor is signed in: end one other session, every other one, or every session. */
@Composable
internal fun SessionsSection(model: DeviceSessionsModel, onSignedOut: () -> Unit) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("sessions-section")) {
        Text(stringResource(R.string.acct_where_youre_signed_in), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        when (val phase = model.phase) {
            LoadPhase.Loading -> CircularProgressIndicator(modifier = Modifier.contentDescribed(R.string.acct_loading_where_youre_signed_in))
            is LoadPhase.Failed -> {
                ErrorLine(phase.message, "sessions-load-error")
                OutlinedButton(onClick = model::load, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.common_try_again)) }
            }
            LoadPhase.Ready -> {
                model.sessions.forEach { session ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(if (session.current) stringResource(R.string.acct_session_this_device, session.label) else session.label)
                            Text(stringResource(R.string.acct_session_last_used, session.lastUsedAt.format(lastUsedFormat)), style = MaterialTheme.typography.bodySmall)
                        }
                        if (!session.current) {
                            TextButton(onClick = { model.revoke(session.id) }, enabled = !model.busy,
                                modifier = Modifier.heightIn(min = 48.dp).testTag("session-revoke-${session.id}")
                                    .contentDescribed(R.string.acct_sign_out_session, session.label)) { Text(stringResource(R.string.common_sign_out)) }
                        }
                    }
                }
                if (model.others.isEmpty()) {
                    Text(stringResource(R.string.acct_youre_not_signed_in_anywhere_else), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sessions-none"))
                } else {
                    OutlinedButton(onClick = model::revokeOthers, enabled = !model.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("sessions-revoke-others")) {
                        Text(stringResource(R.string.acct_sign_out_of_every_other_device))
                    }
                }
            }
        }
        model.notice?.let { StatusLine(it, "sessions-notice") }
        model.actionError?.let { ErrorLine(it, "sessions-error") }
        OutlinedButton(onClick = model::signOutEverywhere, enabled = !model.busy,
            modifier = Modifier.heightIn(min = 48.dp).testTag("sessions-sign-out-everywhere")) { Text(stringResource(R.string.acct_sign_out_on_every_device)) }
    }
}

/** "Add to Google Wallet", shown only when the deployment issues passes; the validated save link opens Google's page. */
@Composable
internal fun WalletPassButton(model: WalletPassModel) {
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) { model.check() }
    LaunchedEffect(model.saveUrl) {
        model.saveUrl?.let { url ->
            model.saveUrl = null
            try {
                uriHandler.openUri(url)
            } catch (_: android.content.ActivityNotFoundException) {
                model.openFailed()
            }
        }
    }
    if (model.available == true) {
        OutlinedButton(onClick = model::prepare, enabled = !model.preparing, modifier = Modifier.heightIn(min = 48.dp).testTag("wallet-add")) {
            Text(if (model.preparing) stringResource(R.string.acct_preparing_your_wallet_pass) else stringResource(R.string.acct_add_to_google_wallet))
        }
    } else if (model.checkFailed) {
        Text(stringResource(R.string.acct_wallet_availability_couldnt_be), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = model::check, modifier = Modifier.heightIn(min = 48.dp).testTag("wallet-recheck")) { Text(stringResource(R.string.acct_check_wallet_availability_again)) }
    }
    model.error?.let { ErrorLine(it, "wallet-error") }
}
