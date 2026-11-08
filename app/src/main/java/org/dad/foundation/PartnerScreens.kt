package org.dad.foundation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.scale
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Partner sign-in and portal (PRD P-*, EM-*, §7). Approved partner users sign in with an emailed link; the portal
// shows verified-or-hidden figures (§7.3), the workforce rollup with <5 suppression (EM-3), and the organization
// tools gated to admins (§7.2): profile, logo, users, company code and report exports.

@Composable
private fun ErrorLine(message: UiText, tag: String) = ErrorLine(message.asString(), tag)

@Composable
private fun ErrorLine(message: String, tag: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag(tag).semantics { liveRegion = LiveRegionMode.Polite })
}

/** Email → "check your email" → the tapped (or pasted) link signs the partner in. */
@Composable
fun PartnerSignInFlow(
    model: PartnerSignInModel,
    incomingToken: String?,
    onTokenConsumed: () -> Unit,
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
    /** The website's partner application (`<public origin>/apply`); null when no public origin is configured. */
    applyUrl: String? = null,
    /** Where this install's partner application stands, when it holds a receipt for one; null hides the card. */
    applicationStatus: ApplicationStatusService? = null,
    /** Submits a partner application from the app (`applyWithReceipt`); null falls back to the website's form. */
    applicationSubmit: PartnerApplicationService? = null,
) {
    LaunchedEffect(incomingToken) {
        val token = incomingToken ?: return@LaunchedEffect
        onTokenConsumed()
        model.accept(token)
    }
    LaunchedEffect(model.phase) { if (model.phase is PartnerSignInPhase.SignedIn) onSignedIn() }
    var applying by rememberSaveable { mutableStateOf(false) }
    // While this install holds an application the server still shows, stop offering to apply again.
    var holdsApplication by rememberSaveable { mutableStateOf(false) }
    // Reloads the status card after a submission so the newly stored receipt is read at once.
    var statusReload by rememberSaveable { mutableIntStateOf(0) }
    AuthColumn("partner-sign-in") {
        Text(stringResource(R.string.partner_partner_sign_in), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        val phase = model.phase
        val sentTo = model.sentTo
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
            when {
                sentTo != null && phase !is PartnerSignInPhase.SignedIn -> PartnerCheckEmail(model, sentTo)
                phase is PartnerSignInPhase.Accepting || phase is PartnerSignInPhase.SignedIn ->
                    CircularProgressIndicator(modifier = Modifier.testTag("partner-signing-in"))
                applying && applicationSubmit != null -> PartnerApplicationForm(
                    service = applicationSubmit,
                    onCancel = { applying = false },
                    onSubmitted = { applying = false; statusReload++ },
                )
                else -> {
                    if (applicationStatus != null) ApplicationStatusCard(applicationStatus, statusReload) { holdsApplication = it }
                    Text(stringResource(R.string.partner_well_email_you_a_link_to_sign_in))
                    OutlinedTextField(
                        value = model.email, onValueChange = { model.email = it }, label = { Text(stringResource(R.string.partner_work_email)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth().testTag("partner-sign-in-email"),
                    )
                    if (phase is PartnerSignInPhase.Failed) ErrorLine(phase.message, "partner-sign-in-error")
                    Button(onClick = model::submit, enabled = model.canSubmit, modifier = Modifier.fillMaxWidth().testTag("partner-sign-in-submit")) {
                        Text(if (phase is PartnerSignInPhase.Submitting) stringResource(R.string.partner_sending) else stringResource(R.string.common_email_me_a_sign_in_link))
                    }
                    if (applicationSubmit != null && !holdsApplication) {
                        OutlinedButton(onClick = { applying = true }, modifier = Modifier.fillMaxWidth().testTag("partner-apply-in-app")) {
                            Text(stringResource(R.string.partner_new_partner_apply_for_your))
                        }
                    }
                    if (applyUrl != null) {
                        val context = LocalContext.current
                        TextButton(
                            onClick = { try { CustomTabsIntent.Builder().build().launchUrl(context, applyUrl.toUri()) } catch (_: ActivityNotFoundException) { } },
                            modifier = Modifier.fillMaxWidth().testTag("partner-apply"),
                        ) { Text(if (applicationSubmit == null) stringResource(R.string.partner_new_partner_apply_on_the_website) else stringResource(R.string.partner_prefer_the_website_apply_there)) }
                    }
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().testTag("partner-back")) { Text(stringResource(R.string.common_back)) }
        }
    }
}

/**
 * The application form: the website's fields, submitted natively. After an unconfirmed attempt the form locks and
 * the button becomes "Retry", which resends exactly what was sent. A `.contain` container keeps each field's own
 * test tag (a bare container tag would flatten them).
 */
@Composable
private fun PartnerApplicationForm(service: PartnerApplicationService, onCancel: () -> Unit, onSubmitted: () -> Unit) {
    val model: PartnerApplicationModel = viewModel(key = "partner-application-form", factory = PartnerApplicationModel.factory(service))
    LaunchedEffect(model.phase) { if (model.phase == ApplicationSubmitPhase.Submitted) onSubmitted() }
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("partner-application-form").semantics(mergeDescendants = false) {},
    ) {
        Text(stringResource(R.string.partner_apply_as_a_partner), style = MaterialTheme.typography.titleLarge, modifier = Modifier.dadHeading())
        Text(stringResource(R.string.partner_tell_us_about_your_organization), style = MaterialTheme.typography.bodyMedium)
        ApplicationField(stringResource(R.string.partner_organization), model.organization, { model.organization = it }, "partner-app-organization", model.isLocked)
        PartnerTypePicker(model.type, model.isLocked) { model.type = it }
        ApplicationField(stringResource(R.string.partner_website), model.website, { model.website = it }, "partner-app-website", model.isLocked, KeyboardType.Uri)
        ApplicationField(stringResource(R.string.partner_approximate_workforce_size), model.workforce, { model.workforce = it }, "partner-app-workforce", model.isLocked, KeyboardType.Number)
        ApplicationField(stringResource(R.string.partner_your_name), model.contactName, { model.contactName = it }, "partner-app-contact", model.isLocked)
        ApplicationField(stringResource(R.string.partner_your_title), model.title, { model.title = it }, "partner-app-title", model.isLocked)
        ApplicationField(stringResource(R.string.partner_work_email), model.workEmail, { model.workEmail = it }, "partner-app-email", model.isLocked, KeyboardType.Email)
        ApplicationField(stringResource(R.string.partner_phone), model.phone, { model.phone = it }, "partner-app-phone", model.isLocked, KeyboardType.Phone)
        val phase = model.phase
        if (phase is ApplicationSubmitPhase.Failed) ErrorLine(phase.message, "partner-app-error")
        Button(onClick = model::submit, enabled = model.canSubmit, modifier = Modifier.fillMaxWidth().testTag("partner-app-submit")) {
            Text(when {
                phase is ApplicationSubmitPhase.Submitting -> stringResource(R.string.partner_sending)
                model.isLocked -> stringResource(R.string.partner_retry)
                else -> stringResource(R.string.partner_submit_application)
            })
        }
        OutlinedButton(onClick = onCancel, enabled = phase != ApplicationSubmitPhase.Submitting,
            modifier = Modifier.fillMaxWidth().testTag("partner-app-cancel")) { Text(stringResource(R.string.common_cancel)) }
    }
}

@Composable
private fun ApplicationField(
    label: String, value: String, onChange: (String) -> Unit, tag: String, locked: Boolean,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, enabled = !locked,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun PartnerTypePicker(selected: PartnerOrganizationType, locked: Boolean, onSelect: (PartnerOrganizationType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.partner_organization_type), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            PartnerOrganizationType.entries.forEach { option ->
                FilterChip(
                    selected = selected == option, onClick = { if (!locked) onSelect(option) }, enabled = !locked,
                    label = { Text(option.label.asString(), maxLines = 1, softWrap = false) },
                    modifier = Modifier.testTag("partner-app-type-${option.name.lowercase()}"),
                )
            }
        }
    }
}

/**
 * The applicant's status, shown above partner sign-in only when the server recognizes this install's receipt.
 * [reloadKey] bumps after a fresh submission so the newly stored receipt is read at once; [onHolds] tells the
 * sign-in screen a status is showing, so it stops offering to apply again.
 */
@Composable
private fun ApplicationStatusCard(service: ApplicationStatusService, reloadKey: Int = 0, onHolds: (Boolean) -> Unit = {}) {
    val model: ApplicationStatusModel = viewModel(
        key = "partner-application-status", factory = ApplicationStatusModel.factory(service))
    LaunchedEffect(reloadKey) { if (reloadKey > 0) model.load() }
    LaunchedEffect(model.phase) { onHolds(model.phase is ApplicationStatusPhase.Loaded) }
    when (val phase = model.phase) {
        is ApplicationStatusPhase.Loaded -> Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(16.dp).testTag("partner-application-status"),
        ) {
            Text(phase.info.title.asString(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
            Text(phase.info.message.asString(), modifier = Modifier.testTag("partner-application-message"))
        }
        is ApplicationStatusPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorLine(phase.message, "partner-application-error")
            OutlinedButton(onClick = model::load, modifier = Modifier.testTag("partner-application-retry")) { Text(stringResource(R.string.common_try_again)) }
        }
        ApplicationStatusPhase.Loading, ApplicationStatusPhase.None -> Unit
    }
}

@Composable
private fun PartnerCheckEmail(model: PartnerSignInModel, email: String) {
    var pasted by rememberSaveable { mutableStateOf("") }
    val phase = model.phase
    Text(stringResource(R.string.partner_check_your_email), style = MaterialTheme.typography.titleLarge, modifier = Modifier.dadHeading())
    Text(stringResource(R.string.partner_sent_sign_in_link, email), modifier = Modifier.testTag("partner-check-email"))
    Text(stringResource(R.string.partner_opened_the_email_somewhere_else), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = pasted, onValueChange = { pasted = it }, label = { Text(stringResource(R.string.common_sign_in_link)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth().testTag("partner-paste-link"),
    )
    if (phase is PartnerSignInPhase.Failed) ErrorLine(phase.message, "partner-sign-in-error")
    val accepting = phase is PartnerSignInPhase.Accepting
    Button(
        onClick = { model.acceptPasted(pasted) }, enabled = pasted.isNotBlank() && !accepting,
        modifier = Modifier.fillMaxWidth().testTag("partner-paste-submit"),
    ) { Text(if (accepting) stringResource(R.string.partner_signing_in) else stringResource(R.string.partner_sign_in_with_this_link)) }
    OutlinedButton(onClick = model::edit, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_use_a_different_email)) }
}

private enum class PartnerSection(@StringRes val label: Int) {
    OVERVIEW(R.string.partner_overview), INITIATIVES(R.string.partner_initiatives), PEOPLE(R.string.partner_people), ORGANIZATION(R.string.partner_organization),
}

/** The signed-in partner portal. [publicOrigin] builds the company sign-up link; blank hides it. */
@Composable
fun PartnerPortal(model: PartnerModel, publicOrigin: String, onSignedOut: () -> Unit = {}, pushTokens: PushTokenProvider? = null) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(pushTokens) {
        val token = try {
            pushTokens?.token()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        token?.let(model::registerPush)
    }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    PartnerReportShare(model)
    var section by rememberSaveable { mutableStateOf(PartnerSection.OVERVIEW) }
    val snapshot = model.snapshot
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("partner-portal"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val logo = remember(snapshot?.logo) { snapshot?.logo?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            if (logo != null) {
                Image(logo.asImageBitmap(), contentDescription = stringResource(R.string.partner_organization_logo), modifier = Modifier.size(44.dp).testTag("partner-logo"))
            }
            Column {
                Text(snapshot?.overview?.organization ?: stringResource(R.string.partner_partner_portal), style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.dadHeading().testTag("partner-organization"))
                snapshot?.let { Text(if (it.overview.role == PartnerRole.ADMIN) stringResource(R.string.partner_admin) else stringResource(R.string.partner_viewer), style = MaterialTheme.typography.bodySmall) }
            }
        }
        val phase = model.phase
        when {
            snapshot == null && phase is PartnerPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(phase.message.asString(), modifier = Modifier.testTag("partner-error"))
                DadPrimaryButton(stringResource(R.string.common_try_again)) { model.load() }
            }
            snapshot == null -> CircularProgressIndicator(modifier = Modifier.testTag("partner-loading"))
            else -> {
                // Scrolls sideways so a long label ("Organization") or a large font keeps each tab on one line.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    PartnerSection.entries.forEach {
                        FilterChip(selected = section == it, onClick = { section = it }, label = { Text(stringResource(it.label), maxLines = 1, softWrap = false) },
                            modifier = Modifier.testTag("partner-tab-${it.name.lowercase()}"))
                    }
                }
                if (snapshot.sectionsUnavailable) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("partner-partial")) {
                        Text(stringResource(R.string.partner_some_details_couldnt_be_loaded), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = model::load) { Text(stringResource(R.string.common_try_again)) }
                    }
                }
                model.notice?.let {
                    Text(it.asString(), modifier = Modifier.testTag("partner-notice").semantics { liveRegion = LiveRegionMode.Polite })
                }
                model.actionError?.let { ErrorLine(it, "partner-action-error") }
                when (section) {
                    PartnerSection.OVERVIEW -> PartnerOverviewSection(snapshot)
                    PartnerSection.INITIATIVES -> PartnerInitiativesSection(snapshot.initiatives)
                    PartnerSection.PEOPLE -> PartnerPeopleSection(snapshot)
                    PartnerSection.ORGANIZATION -> PartnerOrganizationSection(model, snapshot, publicOrigin)
                }
            }
        }
    }
}

/** Headline figures render ONLY when verified (§7.3); an unverified figure is left out, never estimated. */
@Composable
private fun PartnerOverviewSection(snapshot: PartnerSnapshot) {
    val overview = snapshot.overview
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        overview.verifiedLine?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("partner-verified-line")) }
        overview.committed.label?.let { Text(stringResource(R.string.partner_committed, it), modifier = Modifier.testTag("partner-committed")) }
        overview.deployed.label?.let { Text(stringResource(R.string.partner_deployed_percent, it, overview.deployedPercent), modifier = Modifier.testTag("partner-deployed")) }
        overview.peopleReached.label?.let { Text(stringResource(R.string.partner_people_reached, it), modifier = Modifier.testTag("partner-reached")) }
        overview.enrolledLabel?.let { Text(stringResource(R.string.partner_enrolled, it.asString()), modifier = Modifier.testTag("partner-enrolled")) }
    }
    if (snapshot.spend.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("partner-spend")) {
            Text(stringResource(R.string.partner_where_it_went), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
            snapshot.spend.forEach { Text(stringResource(R.string.partner_label_value, it.category, it.amountLabel)) }
        }
    }
}

@Composable
private fun PartnerInitiativesSection(initiatives: List<PartnerInitiative>) {
    if (initiatives.isEmpty()) Text(stringResource(R.string.partner_no_initiatives_yet), modifier = Modifier.testTag("partner-initiatives-empty"))
    initiatives.forEach { initiative ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.testTag("partner-initiative-${initiative.id}")) {
            Text(initiative.title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.common_pair_dot, initiative.status.label.asString(), initiative.region), style = MaterialTheme.typography.bodySmall)
            Text(initiative.summary)
            Text(stringResource(R.string.partner_initiative_amounts, initiative.committedLabel, initiative.deployedLabel), style = MaterialTheme.typography.bodySmall)
            initiative.headlineMetrics.forEach { Text(stringResource(R.string.partner_label_value, it.label, it.valueLabel), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun PartnerPeopleSection(snapshot: PartnerSnapshot) {
    snapshot.people?.let { people ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.partner_our_people), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
            val notice = people.suppressionNotice
            if (notice != null) {
                Text(notice.asString(), modifier = Modifier.testTag("partner-suppressed"))
            } else {
                Text(stringResource(R.string.partner_enrolled_of_total, people.enrolled, people.workforceSize))
                people.givenLabel?.let { Text(stringResource(R.string.partner_given, it), modifier = Modifier.testTag("partner-given")) }
                people.matchedLabel?.let { Text(stringResource(R.string.partner_matched, it)) }
                Text(pluralStringResource(R.plurals.partner_average_streak_days, people.avgStreakDays.toQuantity(), people.avgStreakDays))
                Text(pluralStringResource(R.plurals.partner_volunteered_hours, (people.volunteerMinutes / 60).toQuantity(), people.volunteerMinutes / 60))
            }
        }
    }
    if (snapshot.teams.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.partner_teams), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
            Text(stringResource(R.string.partner_teams_with_fewer_than_5_people_are), style = MaterialTheme.typography.bodySmall)
            snapshot.teams.forEachIndexed { index, team ->
                Text(pluralStringResource(R.plurals.partner_team_line, team.activeGivers.toQuantity(), index + 1, team.name, team.activeGivers, formatMinorUsd(team.annualizedMinor)),
                    modifier = Modifier.testTag("partner-team-${team.id}"))
            }
        }
    }
}

@Composable
private fun PartnerOrganizationSection(model: PartnerModel, snapshot: PartnerSnapshot, publicOrigin: String) {
    PartnerProfileEditor(model, snapshot)
    PartnerCompanyCode(snapshot.overview.companyCode, publicOrigin)
    PartnerUsersSection(model, snapshot)
    if (model.role.canExport) PartnerReportsSection(model, snapshot)
    OutlinedButton(onClick = model::signOut, modifier = Modifier.fillMaxWidth().testTag("partner-sign-out")) { Text(stringResource(R.string.common_sign_out)) }
}

@Composable
private fun PartnerProfileEditor(model: PartnerModel, snapshot: PartnerSnapshot) {
    val overview = snapshot.overview
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.partner_organization), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        if (!model.role.canEditProfile) {
            Text(overview.organization, style = MaterialTheme.typography.titleSmall)
            if (overview.description.isNotEmpty()) Text(overview.description)
            Text(stringResource(R.string.partner_employees, overview.workforceSize), style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        var organization by rememberSaveable { mutableStateOf(overview.organization) }
        var about by rememberSaveable { mutableStateOf(overview.description) }
        var workforce by rememberSaveable { mutableStateOf(overview.workforceSize.toString()) }
        OutlinedTextField(value = organization, onValueChange = { organization = it }, label = { Text(stringResource(R.string.partner_organization_name)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("partner-profile-name"))
        OutlinedTextField(value = about, onValueChange = { about = it }, label = { Text(stringResource(R.string.partner_description)) }, minLines = 3,
            modifier = Modifier.fillMaxWidth().testTag("partner-profile-description"))
        Button(onClick = { model.saveProfile(organization, about) }, enabled = !model.isBusy,
            modifier = Modifier.fillMaxWidth().testTag("partner-profile-save")) { Text(stringResource(R.string.partner_save_profile)) }
        OutlinedTextField(value = workforce, onValueChange = { workforce = it }, label = { Text(stringResource(R.string.partner_number_of_employees)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("partner-workforce"))
        Text(stringResource(R.string.partner_enrollment_percentages_are), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { model.saveWorkforce(workforce) }, enabled = !model.isBusy,
            modifier = Modifier.fillMaxWidth().testTag("partner-workforce-save")) { Text(stringResource(R.string.partner_save_workforce_size)) }
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) scope.launch {
                val png = withContext(Dispatchers.IO) { LogoImage.png(context, uri) }
                if (png == null) model.rejectLogo() else model.saveLogo(png)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !model.isBusy, modifier = Modifier.testTag("partner-logo-pick")) { Text(if (snapshot.logo == null) stringResource(R.string.partner_add_logo) else stringResource(R.string.partner_change_logo)) }
            if (snapshot.logo != null) {
                OutlinedButton(onClick = { model.saveLogo(null) }, enabled = !model.isBusy, modifier = Modifier.testTag("partner-logo-remove")) { Text(stringResource(R.string.partner_remove_logo)) }
            }
        }
    }
}

/** Scales a picked image to the server's logo limits: a PNG no larger than 512 by 512 pixels and 256 KiB. */
internal object LogoImage {
    private const val MAX_SOURCE_BYTES = 20 * 1024 * 1024
    private const val MAX_PNG_BYTES = 256 * 1024

    fun png(context: Context, uri: Uri): ByteArray? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_SOURCE_BYTES) return null
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decode no larger than needed: a full-resolution camera photo would cost hundreds of megabytes.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 512) sample *= 2
        val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        var side = 512
        while (side >= 64) {
            val scale = minOf(1f, side.toFloat() / maxOf(image.width, image.height))
            val scaled = image.scale(maxOf(1, (image.width * scale).toInt()), maxOf(1, (image.height * scale).toInt()))
            val png = ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            if (png.size <= MAX_PNG_BYTES) return png
            side /= 2
        }
        return null
    }
}

/** The company code employees enroll with (EM-1), as a sign-up link and its QR code. */
@Composable
private fun PartnerCompanyCode(code: String, publicOrigin: String) {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.partner_share_sign_up_link)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.common_company_code), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Text(code, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace), modifier = Modifier.testTag("partner-company-code"))
        if (publicOrigin.isBlank()) return@Column
        val link = publicOrigin.trimEnd('/') + "/c/" + code
        val qr = remember(link) { runCatching { qrBitmap(link) }.getOrNull() }
        if (qr != null) {
            Image(qr.asImageBitmap(), contentDescription = stringResource(R.string.partner_qr_code_for, link), filterQuality = FilterQuality.None,
                modifier = Modifier.size(160.dp).testTag("partner-company-qr"))
        }
        OutlinedButton(
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, link) }
                try { context.startActivity(Intent.createChooser(send, shareTitle)) } catch (_: ActivityNotFoundException) { }
            },
            modifier = Modifier.testTag("partner-company-share"),
        ) { Text(stringResource(R.string.partner_share_sign_up_link)) }
    }
}

/** Users: an admin invites and removes inline (never themselves or the last admin); a viewer reads the list. */
@Composable
private fun PartnerUsersSection(model: PartnerModel, snapshot: PartnerSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.partner_users), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        snapshot.users.forEach { user ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val isSelf = user.id == snapshot.selfId
                Text(stringResource(if (isSelf) R.string.partner_user_line_self else R.string.partner_user_name_role, user.name, stringResource(if (user.role == PartnerRole.ADMIN) R.string.partner_admin else R.string.partner_viewer)), modifier = Modifier.weight(1f))
                if (model.canRemove(user)) {
                    if (model.pendingRemoval?.id == user.id) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = model::confirmRemoval, modifier = Modifier.testTag("partner-remove-confirm-${user.id}")
                                .contentDescribed(R.string.partner_confirm_remove, user.name)) { Text(stringResource(R.string.partner_yes_remove)) }
                            OutlinedButton(onClick = model::cancelRemoval, modifier = Modifier.testTag("partner-remove-keep-${user.id}")) { Text(stringResource(R.string.partner_keep)) }
                        }
                    } else {
                        OutlinedButton(onClick = { model.requestRemoval(user.id) }, modifier = Modifier.testTag("partner-remove-${user.id}")
                            .contentDescribed(R.string.partner_remove_user, user.name)) { Text(stringResource(R.string.common_remove)) }
                    }
                }
            }
        }
        if (!model.role.canManageUsers) {
            Text(stringResource(R.string.partner_read_only_access), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("partner-readonly"))
            return@Column
        }
        var inviteName by rememberSaveable { mutableStateOf("") }
        var inviteEmail by rememberSaveable { mutableStateOf("") }
        var inviteAdmin by rememberSaveable { mutableStateOf(false) }
        OutlinedTextField(value = inviteName, onValueChange = { inviteName = it }, label = { Text(stringResource(R.string.partner_their_name)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("partner-invite-name"))
        OutlinedTextField(value = inviteEmail, onValueChange = { inviteEmail = it }, label = { Text(stringResource(R.string.partner_their_work_email)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth().testTag("partner-invite-email"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !inviteAdmin, onClick = { inviteAdmin = false }, label = { Text(stringResource(R.string.partner_viewer)) }, modifier = Modifier.testTag("partner-invite-viewer"))
            FilterChip(selected = inviteAdmin, onClick = { inviteAdmin = true }, label = { Text(stringResource(R.string.partner_admin)) }, modifier = Modifier.testTag("partner-invite-admin"))
        }
        Button(
            onClick = {
                model.inviteUser(inviteEmail, inviteName, if (inviteAdmin) PartnerRole.ADMIN else PartnerRole.VIEWER) {
                    inviteEmail = ""
                    inviteName = ""
                }
            },
            enabled = isPlausibleEmail(inviteEmail) && inviteName.isNotBlank() && !model.isBusy,
            modifier = Modifier.fillMaxWidth().testTag("partner-invite"),
        ) { Text(stringResource(R.string.partner_invite_user)) }
    }
}

/** Admin-only PDF impact reports (P-14): a new export, and earlier ones while they are retained. */
@Composable
private fun PartnerReportsSection(model: PartnerModel, snapshot: PartnerSnapshot) {
    val dates = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.partner_reports), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        Button(onClick = model::exportReport, enabled = !model.isBusy, modifier = Modifier.fillMaxWidth().testTag("partner-export")) {
            Text(if (model.isBusy) stringResource(R.string.partner_preparing) else stringResource(R.string.partner_export_impact_report))
        }
        snapshot.exports.forEach { export ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(dates.format(export.createdAt))
                    Text(stringResource(R.string.partner_export_line, export.exportedBy, formatMinorUsd(export.committedMinor)), style = MaterialTheme.typography.bodySmall)
                }
                if (export.downloadable) {
                    OutlinedButton(onClick = { model.download(export) }, enabled = !model.isBusy,
                        modifier = Modifier.testTag("partner-export-${export.id}")) { Text(stringResource(R.string.partner_download)) }
                } else {
                    Text(stringResource(R.string.partner_no_longer_kept), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Offers a fetched report through the share sheet via a FileProvider URI, then forgets the bytes. */
@Composable
private fun PartnerReportShare(model: PartnerModel) {
    val context = LocalContext.current
    val report = model.report
    val shareTitle = stringResource(R.string.partner_share_report)
    LaunchedEffect(report?.id) {
        if (report == null) return@LaunchedEffect
        val file = withContext(Dispatchers.IO) { sharedFile(context, "reports", report.name).also { it.writeBytes(report.pdf) } }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(context, fileAuthority(context), file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { context.startActivity(Intent.createChooser(send, shareTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        catch (_: ActivityNotFoundException) { }
        model.reportShared()
    }
}
