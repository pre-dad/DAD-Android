package org.dad.foundation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler

private enum class AuthStep { RoleSelect, Methods, Phone, EmailLink, Password, Signup, Partner }

/**
 * The unauthenticated stack: role select -> sign-in methods -> the chosen method's flow. Verification
 * runs through [establish] so the session (not this screen) owns the network await and its epoch guard.
 */
@Composable
fun UnauthenticatedStack(
    notice: UiText?,
    /** "<name> invited you", after an invite link was opened before sign-in. */
    invitation: UiText? = null,
    auth: AuthServices,
    establish: Establish,
    generation: Int,
    onSignedIn: () -> Unit,
    /** Claims a sign-in link pasted by the donor; false when the text is not a DAD sign-in link. */
    onPastedLink: (String) -> Boolean = { false },
    partnerSignIn: PartnerSignInService? = null,
    applicationStatus: ApplicationStatusService? = null,
    applicationSubmit: PartnerApplicationService? = null,
    /** A tapped partner sign-in link's token, accepted on the partner sign-in screen. */
    partnerToken: String? = null,
    onPartnerTokenConsumed: () -> Unit = {},
    onPartnerSignedIn: () -> Unit = {},
    analytics: AnalyticsUploader? = null,
    /** The configured public site; blank where none is configured. */
    publicOrigin: String = "",
) {
    var step by rememberSaveable { mutableStateOf(AuthStep.RoleSelect) }
    LaunchedEffect(partnerToken) { if (partnerToken != null) step = AuthStep.Partner }
    BackHandler(enabled = step != AuthStep.RoleSelect) {
        step = if (step == AuthStep.Phone || step == AuthStep.EmailLink || step == AuthStep.Password || step == AuthStep.Signup) AuthStep.Methods
        else AuthStep.RoleSelect
    }
    when (step) {
        AuthStep.RoleSelect -> RoleSelect(notice, invitation) { role ->
            analytics?.emit(AnalyticsEvent.Plain(PlainEvent.ROLE_SELECTED))
            step = if (role == AppRole.DONOR) AuthStep.Methods else AuthStep.Partner
        }
        AuthStep.Methods -> SignInMethods(
            auth.external, establish, generation, onSignedIn,
            onPhone = { step = AuthStep.Phone }, onEmailLink = { step = AuthStep.EmailLink }, onPassword = { step = AuthStep.Password },
            onSignup = { step = AuthStep.Signup },
            onBack = { step = AuthStep.RoleSelect })
        AuthStep.Phone -> PhoneSignIn(
            // Keyed by session generation so a model that ended in SignedIn is never reused for the next sign-in.
            viewModel(key = "phone-sign-in-$generation", factory = PhoneSignInModel.factory(auth.phone, establish)), onSignedIn)
        AuthStep.EmailLink -> EmailLinkFlow(
            viewModel(key = "email-link-$generation", factory = SignInLinkModel.factory(auth.link)),
            onPastedLink, notice, onBack = { step = AuthStep.Methods })
        AuthStep.Password -> PasswordSignInFlow(
            viewModel(key = "password-sign-in-$generation", factory = PasswordSignInModel.factory(auth.password, establish)),
            onSignedIn, onBack = { step = AuthStep.Methods })
        AuthStep.Signup -> SignupFlow(
            viewModel(key = "signup-$generation", factory = SignupModel.factory(auth.signup)),
            onPastedLink, notice, onBack = { step = AuthStep.Methods })
        AuthStep.Partner -> if (partnerSignIn != null) PartnerSignInFlow(
            viewModel(key = "partner-sign-in-$generation", factory = PartnerSignInModel.factory(partnerSignIn)),
            incomingToken = partnerToken, onTokenConsumed = onPartnerTokenConsumed,
            onBack = { step = AuthStep.RoleSelect }, onSignedIn = onPartnerSignedIn,
            applyUrl = publicOrigin.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/')}/apply" },
            applicationStatus = applicationStatus,
            applicationSubmit = applicationSubmit,
        ) else RoleSelect(PhoneSignInModel.NOT_CONFIGURED) { role -> if (role == AppRole.DONOR) step = AuthStep.Methods }
    }
}

@Composable
internal fun AuthColumn(tag: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag(tag),
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/** The DAD brand mark as a small rounded tile (brand fill, ink "D"): a shape + letter, not colour alone,
 *  with a description so TalkBack names the mark. No icon dependency is added. */
@Composable
private fun BrandTile() {
    Box(
        modifier = Modifier
            .background(DadTheme.color("brand"), RoundedCornerShape(DadTheme.radius("field")))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .contentDescribed(R.string.auth_brand_tile_description),
        contentAlignment = Alignment.Center,
    ) { Text(stringResource(R.string.auth_brand_tile_letter), style = MaterialTheme.typography.headlineMedium, color = DadTheme.color("ink")) }
}

/**
 * Days remaining until the Founding-Donor deadline (end of day 2026-12-31 America/New_York), or null
 * once that instant has passed so the line is dropped. One helper so the deadline is computed — and the
 * line rendered — at most once per screen.
 */
internal fun foundingDaysRemaining(now: java.time.Instant = java.time.Instant.now()): Long? {
    val zone = java.time.ZoneId.of("America/New_York")
    val deadline = java.time.LocalDate.of(2026, 12, 31).atTime(23, 59, 59).atZone(zone).toInstant()
    if (!now.isBefore(deadline)) return null
    val days = java.time.Duration.between(now, deadline).toDays()
    return days + 1 // count the current partial day as a full day remaining
}

/** Unauthenticated splash: pick a journey before signing in. Shows [notice] (a failed link, a revoked
 *  session, or an unconfigured build) so the reason for being here is never silent. */
@Composable
fun RoleSelect(notice: UiText?, invitation: UiText? = null, onSelect: (AppRole) -> Unit) {
    // Welcome sits on the ink surface. Foreground tokens are AA on ink (asserted in ContrastTest):
    // paper for the title, dkmut for the eyebrow/sub/founding line, brand for the primary fill.
    Box(Modifier.fillMaxSize().background(DadTheme.color("ink"))) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("role-select"),
            verticalArrangement = Arrangement.Center,
        ) {
            BrandTile()
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.auth_aid_delivered), style = MaterialTheme.typography.labelLarge, color = DadTheme.color("dkmut"))
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.auth_a_dollar_a_day_every_dollar_on_the), style = MaterialTheme.typography.headlineMedium,
                color = DadTheme.color("paper"), modifier = Modifier.dadHeading())
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.auth_give_monthly_invite_the_people_you),
                style = MaterialTheme.typography.bodyLarge, color = DadTheme.color("dkmut"))
            // Founding-Donor line, computed days remaining; hidden after end-of-2026 New York time.
            remember { foundingDaysRemaining() }?.let { days ->
                Spacer(Modifier.height(12.dp))
                Text(pluralStringResource(R.plurals.auth_founding_deadline, days.toQuantity(), days),
                    style = MaterialTheme.typography.bodyMedium, color = DadTheme.color("dkmut"),
                    modifier = Modifier.testTag("founding-deadline"))
            }
            // KEEP the notice + invitation above the actions.
            if (notice != null) {
                Spacer(Modifier.height(16.dp))
                Text(notice.asString(), style = MaterialTheme.typography.bodyMedium, color = DadTheme.color("paper"),
                    modifier = Modifier.testTag("auth-notice").semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (invitation != null) {
                Spacer(Modifier.height(16.dp))
                Text(invitation.asString(), style = MaterialTheme.typography.bodyLarge, color = DadTheme.color("paper"),
                    modifier = Modifier.testTag("auth-invitation"))
            }
            Spacer(Modifier.height(28.dp))
            // The screen's single filled primary. The DONOR branch leads to the sign-in methods (and
            // from there to the signup flow retitled "Step 1 of 2 · About you").
            DadPrimaryButton(stringResource(R.string.auth_give_1_a_day), Modifier.testTag("role-donor")) { onSelect(AppRole.DONOR) }
            Spacer(Modifier.height(12.dp))
            DadSecondaryButton(stringResource(R.string.auth_i_already_give_sign_in), Modifier.fillMaxWidth()) { onSelect(AppRole.DONOR) }
            Spacer(Modifier.height(8.dp))
            DadTextButton(stringResource(R.string.auth_partner_organization_sign_in_here), Modifier.testTag("role-partner")) { onSelect(AppRole.PARTNER) }
        }
    }
}

/** The analytics question, worded as on the website; the answer can be changed in Settings. Shown once
 *  after the first sign-in (see the DonorShell bottom sheet), not on Welcome. The body is shared so the
 *  copy, options and tags are reused verbatim wherever the question appears. */
@Composable
internal fun AnalyticsChoiceBody(analytics: AnalyticsUploader) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("analytics-choice")) {
        Text(stringResource(R.string.auth_analytics_explainer), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { analytics.choose(true) }, modifier = Modifier.fillMaxWidth().testTag("analytics-allow")) { Text(stringResource(R.string.auth_allow_usage_analytics)) }
        OutlinedButton(onClick = { analytics.choose(false) }, modifier = Modifier.fillMaxWidth().testTag("analytics-decline")) { Text(stringResource(R.string.auth_decline_usage_analytics)) }
    }
}

@Composable
private fun SignInMethods(
    external: ExternalSignInDeps, establish: Establish, generation: Int, onSignedIn: () -> Unit,
    onPhone: () -> Unit, onEmailLink: () -> Unit, onPassword: () -> Unit, onSignup: () -> Unit, onBack: () -> Unit,
) {
    // "More options" holds the lower-emphasis methods; a configuration change keeps it open.
    var moreOptions by rememberSaveable { mutableStateOf(false) }
    AuthColumn("sign-in-methods") {
        Text(stringResource(R.string.common_welcome_back), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(20.dp))
        // The screen's single filled primary.
        DadPrimaryButton(stringResource(R.string.auth_continue_with_phone), Modifier.testTag("method-phone"), onPhone)
        Spacer(Modifier.height(12.dp))
        DadSecondaryButton(stringResource(R.string.common_email_me_a_sign_in_link), Modifier.fillMaxWidth().testTag("method-email-link"), onClick = onEmailLink)
        Spacer(Modifier.height(12.dp))
        DadTextButton(if (moreOptions) stringResource(R.string.auth_fewer_options) else stringResource(R.string.auth_more_options), Modifier.testTag("more-options")) { moreOptions = !moreOptions }
        if (moreOptions) {
            Spacer(Modifier.height(8.dp))
            DadSecondaryButton(stringResource(R.string.auth_sign_in_with_email_and_password), Modifier.fillMaxWidth().testTag("method-password"), onClick = onPassword)
            Spacer(Modifier.height(12.dp))
            // Google/passkey render only when a provider is wired.
            ExternalMethods(external, establish, generation, onSignedIn)
        }
        Spacer(Modifier.height(24.dp))
        // "New to DAD?" is a small bottom card with a secondary "Get started".
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = DadTheme.color("card"),
            border = androidx.compose.foundation.BorderStroke(1.dp, DadTheme.color("line")),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.auth_new_to_dad), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
                DadSecondaryButton(stringResource(R.string.auth_get_started), Modifier.fillMaxWidth().testTag("method-signup"), onClick = onSignup)
            }
        }
        Spacer(Modifier.height(12.dp))
        DadTextButton(stringResource(R.string.common_back), onClick = onBack)
    }
}


/** Phone + SMS one-time-code journey, bound to [PhoneSignInModel]. */
@Composable
private fun PhoneSignIn(model: PhoneSignInModel, onSignedIn: () -> Unit) {
    LaunchedEffect(model.phase) { if (model.phase == PhoneSignInPhase.SignedIn) onSignedIn() }
    val busy = model.phase is PhoneSignInPhase.RequestingCode || model.phase is PhoneSignInPhase.Verifying
    // A complete 6-digit code submits by itself (SMS-OTP autofill delivers all six at once); the
    // explicit Verify button stays for accessibility and manual entry.
    LaunchedEffect(model.code) { if (model.canVerify && !busy && model.isAwaitingCode) model.verify() }
    AuthColumn("phone-sign-in") {
        Text(stringResource(R.string.auth_sign_in_with_your_phone), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Spacer(Modifier.height(16.dp))
        if (model.isAwaitingCode) {
            Text(stringResource(R.string.auth_enter_the_6_digit_code_we_texted))
            OutlinedTextField(
                value = model.code, onValueChange = { model.code = it.filter(Char::isDigit).take(6) },
                label = { Text(stringResource(R.string.auth_verification_code)) }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth().testTag("code-input").semantics { contentType = ContentType.SmsOtpCode },
            )
            Spacer(Modifier.height(12.dp))
            DadPrimaryButton(stringResource(R.string.auth_verify), Modifier.testTag("verify-code")) { if (model.canVerify && !busy) model.verify() }
        } else {
            OutlinedTextField(
                value = model.phone, onValueChange = { model.phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }.take(18) },
                label = { Text(stringResource(R.string.auth_phone_number)) }, singleLine = true, enabled = !busy,
                supportingText = { Text(stringResource(R.string.auth_include_your_country_code)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().testTag("phone-input").semantics { contentType = ContentType.PhoneNumber },
            )
            Spacer(Modifier.height(12.dp))
            DadPrimaryButton(stringResource(R.string.auth_send_code), Modifier.testTag("send-code")) { if (model.canRequestCode && !busy) model.requestCode() }
        }
        if (busy) { Spacer(Modifier.height(12.dp)); CircularProgressIndicator(modifier = Modifier.testTag("sign-in-working")) }
        (model.phase as? PhoneSignInPhase.Failed)?.let {
            Spacer(Modifier.height(12.dp))
            Text(it.message.asString(), modifier = Modifier.testTag("sign-in-error").semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
