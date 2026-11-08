package org.dad.foundation

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Engagement (PRD A-3, I-*, K-*, N-*, R-*, B-*): impact totals, the 24-badge grid, the rankings choice, the
// referral network and the invite link / IRL code, plus the initiative spend drill-in. Bound to [EngagementModel] and
// [InitiativeSpendModel]. State is always carried in text as well as colour, a section the server did not answer says
// so instead of drawing a zero, and nothing here logs.

private val shortDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val longDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(java.util.Locale.US)

/** The unavailable-section line: one wording everywhere so a missing figure is never mistaken for none. */
@Composable
private fun UnavailableLine(what: String, tag: String) {
    Text(stringResource(R.string.eng_cant_be_shown_right_now, what), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(tag))
}

/**
 * "Your impact": lifetime totals, badges, the rankings card with an inline toggle, the network and the invite.
 * [publicOrigin] is the configured public origin the invite link is built from (blank = no link, never an invented one).
 */
@Composable
fun EngagementHome(
    model: EngagementModel,
    publicOrigin: String,
    sharer: ReferralShareAction,
    onClose: (() -> Unit)? = null,
    onSignedOut: () -> Unit = {},
    /** Adds the referral code to Google Wallet; null where Wallet is not wired (previews). */
    wallet: WalletPassModel? = null,
    /** The donor's public name, for the invite preview; null or blank shows a neutral line. */
    inviterName: String? = null,
) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    var showQr by rememberSaveable { mutableStateOf(false) }
    val referral = model.snapshot?.referral
    val irlLink = referral?.irlLink(publicOrigin)
    if (showQr && irlLink != null) {
        BackHandler { showQr = false }
        InviteQrScreen(irlLink, onClose = { showQr = false })
        return
    }
    if (onClose != null) BackHandler(onBack = onClose)
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("engagement-home"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.eng_your_impact), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        when (val phase = model.phase) {
            EngagementPhase.Loading -> CircularProgressIndicator(modifier = Modifier.testTag("engagement-loading"))
            is EngagementPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(phase.message.asString(), modifier = Modifier.testTag("engagement-error"))
                DadPrimaryButton(stringResource(R.string.common_try_again), Modifier.testTag("engagement-retry")) { model.load() }
            }
            EngagementPhase.Ready -> {
                model.refreshNotice?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("engagement-notice")) }
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    model.actionError?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("engagement-action-error")) }
                }
                model.snapshot?.let { Content(model, it, publicOrigin, sharer, wallet, inviterName, onShowQr = { showQr = true; sharer.codeShown() }) }
            }
        }
    }
}

@Composable
private fun Content(
    model: EngagementModel, snapshot: EngagementSnapshot, publicOrigin: String, sharer: ReferralShareAction, wallet: WalletPassModel?,
    inviterName: String?, onShowQr: () -> Unit,
) {
    // Circle leads with the invite: what a friend will see, then the share actions, then the numbers.
    InvitePreview(inviterName)
    InviteCard(model, snapshot, publicOrigin, sharer, wallet, onShowQr)
    ImpactCard(snapshot.impact)
    BadgesSection(snapshot.badges)
    RankingsCard(model)
    NetworkCard(snapshot.network)
}

@Composable
private fun InvitePreview(inviterName: String?) {
    // One merged node: the card reads as a single preview, and its text belongs to the tagged card.
    HomeCard("invite-preview", Modifier.semantics(mergeDescendants = true) {}) {
        val name = inviterName?.trim().orEmpty()
        Text(if (name.isNotEmpty()) stringResource(R.string.eng_name_invited_you_to_dad, name) else stringResource(R.string.eng_your_invite_to_dad), style = MaterialTheme.typography.titleMedium)
        DadGiftFraming(stringResource(R.string.common_30_month), stringResource(R.string.common_1_a_day))
    }
}

// MARK: impact (I-1, I-3)

@Composable
private fun ImpactCard(impact: ImpactTotals?) {
    HomeCard("impact-section") {
        Text(stringResource(R.string.eng_lifetime_given), style = MaterialTheme.typography.labelLarge)
        if (impact != null) {
            Text(impact.givenLabel, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("impact-given"))
            val funded = impact.initiativesFunded
            val events = impact.eventsVolunteered
            Text(
                stringResource(
                    R.string.common_pair_dot,
                    pluralStringResource(R.plurals.eng_initiatives_count, funded, funded),
                    pluralStringResource(R.plurals.eng_events_count, events, events),
                ),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("impact-counts"),
            )
        } else {
            Text("—", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("impact-given").contentDescribed(R.string.common_not_available))
            UnavailableLine(stringResource(R.string.eng_your_impact), "impact-unavailable")
        }
    }
}

// MARK: badges (B-1..B-4), earned/locked in text, never colour alone

@Composable
private fun BadgesSection(grid: BadgeGrid?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.eng_badges), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        if (grid == null) {
            UnavailableLine(stringResource(R.string.eng_your_badges), "badges-unavailable")
            return
        }
        Text(grid.summaryLabel.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("badge-summary"))
        if (grid.hasInviteOnlyAccess) {
            Text(stringResource(R.string.eng_you_have_invite_only_event_access), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("badge-invite-access"))
        }
        grid.badges.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { badge -> BadgeTile(badge, Modifier.weight(1f)) }
                if (row.size < 2) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BadgeTile(badge: Badge, modifier: Modifier = Modifier) {
    // Earned badges render in the brand colour; locked ones greyed. State is ALSO in the label and the detail line,
    // so a colour-blind or screen-reader user gets earned/locked from text (B-1, WCAG).
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val color = if (badge.earned) DadTheme.brandAccent(dark) else DadTheme.color(if (dark) "dkmut" else "muted")
    val label = badge.accessibilityLabel.asString()
    val perk = badge.perk?.takeIf { badge.perkActive }?.asString()
    val description = if (perk != null) stringResource(R.string.eng_badge_description_with_perk, label, perk) else label
    Column(
        modifier = modifier
            .testTag(if (badge.earned) "badge-earned-${badge.code}" else "badge-locked-${badge.code}")
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (badge.earned) DadSymbol("check", stringResource(R.string.eng_earned), tint = color) else DadSymbol("lock", stringResource(R.string.eng_locked), tint = color)
        Text(badge.shownTitle, style = MaterialTheme.typography.labelLarge, color = color)
        Text(if (badge.earned) badge.earnedAt?.let { stringResource(R.string.eng_earned_on, it.format(longDate)) } ?: stringResource(R.string.eng_earned) else stringResource(R.string.eng_locked), style = MaterialTheme.typography.labelSmall, color = color)
        if (!badge.earned) Text(badge.rule, style = MaterialTheme.typography.bodySmall)
        if (perk != null) Text(stringResource(R.string.eng_perk, perk), style = MaterialTheme.typography.bodySmall)
    }
}

// MARK: rankings (K-1..K-3): opt-out hides the position entirely, never a name list

@Composable
private fun RankingsCard(model: EngagementModel) {
    HomeCard("rankings-section") {
        Text(stringResource(R.string.eng_rankings), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        val ranking = model.displayedRanking
        if (ranking == null) {
            UnavailableLine(stringResource(R.string.eng_your_ranking), "ranking-unavailable")
            return@HomeCard
        }
        if (ranking.isOptedOut) {
            Text(stringResource(R.string.eng_youre_not_shown_in_rankings), modifier = Modifier.testTag("ranking-optedout"))
        } else {
            val national = ranking.nationalLabel
            val city = ranking.cityLabel
            national?.let { Text(it.asString(), modifier = Modifier.testTag("ranking-national")) }
            city?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ranking-city")) }
            if (national == null && city == null) Text(stringResource(R.string.common_not_ranked_yet), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("ranking-unranked"))
        }
        OutlinedButton(
            onClick = { model.setRankingOptIn(ranking.isOptedOut) },
            enabled = model.pendingOptIn == null,
            modifier = Modifier.heightIn(min = 48.dp).testTag("ranking-toggle"),
        ) { Text(if (ranking.isOptedOut) stringResource(R.string.common_show_my_ranking) else stringResource(R.string.eng_hide_my_ranking)) }
        Text(stringResource(R.string.eng_only_you_see_your_position), style = MaterialTheme.typography.bodySmall)
    }
}

// MARK: network (N-1..N-3)

@Composable
private fun NetworkCard(network: NetworkView?) {
    HomeCard("network-section") {
        Text(stringResource(R.string.common_your_network), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        if (network == null) {
            UnavailableLine(stringResource(R.string.common_your_network), "network-unavailable")
            return@HomeCard
        }
        Text(network.activeLabel.asString(), style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("network-active"))
        Text(network.annualizedLabel.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("network-annualized"))
        network.nextMilestone?.let { Text(it.progressLabel.asString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("network-milestone")) }
        network.invitees.forEachIndexed { index, invitee ->
            Text(stringResource(R.string.eng_invitee_status, invitee.name, invitee.statusLabel.asString()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("network-invitee-$index"))
        }
        network.anonymousStayingLabel?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("network-anonymous")) }
    }
}

// MARK: invite link + IRL code (R-1..R-4)

@Composable
private fun InviteCard(
    model: EngagementModel, snapshot: EngagementSnapshot, publicOrigin: String, sharer: ReferralShareAction, wallet: WalletPassModel?, onShowQr: () -> Unit,
) {
    HomeCard("invite-section") {
        Text(stringResource(R.string.eng_invite_friends), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        val referral = snapshot.referral
        when {
            referral != null -> Invite(referral, publicOrigin, sharer, wallet, onShowQr)
            snapshot.needsPublicName -> NamePrompt(model)
            else -> UnavailableLine(stringResource(R.string.eng_your_invite_link), "invite-unavailable")
        }
    }
}

@Composable
private fun Invite(referral: ReferralInfo, publicOrigin: String, sharer: ReferralShareAction, wallet: WalletPassModel?, onShowQr: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    val haptics = rememberDadHaptics()
    val context = LocalContext.current
    Text(stringResource(R.string.eng_your_code, referral.code), modifier = Modifier.testTag("referral-code"))
    referral.totalConverted?.let { total ->
        Text(pluralStringResource(R.plurals.eng_people_joined_through_you, total.toQuantity(), total), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("referral-total"))
    } ?: UnavailableLine(stringResource(R.string.eng_your_referral_count), "referral-total-unavailable")
    val link = referral.link(publicOrigin)
    if (link != null) {
        DadPrimaryButton(stringResource(R.string.eng_share_invite), Modifier.testTag("share-invite")) { sharer.share(referral.shareMessage(publicOrigin).resolve(context)); haptics.confirm() }
        DadSecondaryButton(stringResource(R.string.eng_show_qr), Modifier.testTag("show-qr"), onClick = onShowQr)
        wallet?.let { WalletPassButton(it) }
        DadTextButton(if (copied) stringResource(R.string.eng_copied) else stringResource(R.string.eng_copy_link), Modifier.testTag("copy-invite")) { sharer.copy(link); copied = true; haptics.confirm() }
        Text(link, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("invite-link"))
    } else {
        Text(stringResource(R.string.eng_invite_links_arent_available_in), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("invite-unconfigured"))
    }
    Conversions(referral)
}

@Composable
private fun Conversions(referral: ReferralInfo) {
    val list = referral.conversions
    if (list == null) {
        UnavailableLine(stringResource(R.string.eng_your_recent_first_gifts), "conversions-unavailable")
        return
    }
    if (list.isEmpty()) {
        Text(stringResource(R.string.eng_no_one_has_given_through_your_link), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("conversions-empty"))
        return
    }
    Text(stringResource(R.string.eng_recent_first_gifts), style = MaterialTheme.typography.titleSmall)
    list.take(5).forEachIndexed { index, conversion ->
        Text(stringResource(R.string.common_pair_dot, conversion.convertedAt.format(shortDate), conversion.kindLabel.asString()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("conversion-$index"))
    }
    if (list.size > 5 || referral.hasMoreConversions) Text(stringResource(R.string.eng_older_first_gifts_arent_listed), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun NamePrompt(model: EngagementModel) {
    var draft by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.eng_choose_the_name_your_invite_link), style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = draft, onValueChange = { draft = it.take(MAX_PUBLIC_NAME) },
        label = { Text(stringResource(R.string.eng_public_name)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        modifier = Modifier.fillMaxWidth().testTag("name-field"),
    )
    model.nameError?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("name-error")) }
    DadPrimaryButton(stringResource(R.string.eng_create_my_invite_link), Modifier.testTag("save-name"), enabled = !model.isSavingName && draft.isNotBlank()) { model.choosePublicName(draft) }
}

/**
 * The full-screen IRL code (R-1, R-2): a QR of the in-person link, drawn locally from ZXing's matrix so no image is
 * fetched and no untrusted file is parsed. Meant to be scanned from a phone screen face to face.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InviteQrScreen(link: String, onClose: () -> Unit) {
    val bitmap = remember(link) { runCatching { qrBitmap(link) }.getOrNull() }
    // The QR is a pushed sub-screen of Circle: its back arrow (carrying the moved `qr-close` tag) lives in
    // a TopAppBar via DadBackBar, replacing the former inline Close button. BackHandler is wired by the caller.
    Scaffold(
        topBar = { DadBackBar("qr-close", stringResource(R.string.common_back), onBack = onClose) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("qr-screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.eng_show_this_code), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(), contentDescription = stringResource(R.string.eng_qr_code_for_your_invite_link), filterQuality = FilterQuality.None,
                    modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().background(Color.White).padding(16.dp).testTag("qr-code"),
                )
            } else {
                Text(stringResource(R.string.eng_the_code_couldnt_be_drawn_share), modifier = Modifier.testTag("qr-failed"))
            }
            Text(stringResource(R.string.eng_a_friend_scans_it_with_their_phone), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Dark modules black on a white bitmap (a scanner needs the contrast whatever the app theme). */
internal fun qrBitmap(contents: String): Bitmap {
    val matrix = Referral.qrMatrix(contents, size = 256)
    val size = matrix.size
    val pixels = IntArray(size * size) { i -> if (matrix[i / size][i % size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** "See every dollar this initiative has spent" (A-3): spend by category, totals only (PRD §5.2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InitiativeSpendScreen(model: InitiativeSpendModel, title: String, onClose: () -> Unit, onSignedOut: () -> Unit = {}) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    // The spend drill-in is a pushed screen: its back arrow (the moved `spend-close` tag) is in the
    // TopAppBar. BackHandler is wired by the shell that pushed it.
    Scaffold(
        topBar = { DadBackBar("spend-close", stringResource(R.string.common_back), onBack = onClose) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("spend-screen"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.common_where_the_money_went), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
            Text(title, style = MaterialTheme.typography.titleMedium)
            when (val phase = model.phase) {
                InitiativeSpendModel.Phase.Loading -> CircularProgressIndicator(modifier = Modifier.testTag("spend-loading"))
                InitiativeSpendModel.Phase.Unpublished -> Text(stringResource(R.string.eng_this_initiatives_spending_hasnt), modifier = Modifier.testTag("spend-unpublished"))
                is InitiativeSpendModel.Phase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(phase.message.asString(), modifier = Modifier.testTag("spend-error"))
                    DadPrimaryButton(stringResource(R.string.common_try_again), Modifier.testTag("spend-retry")) { model.load() }
                }
                is InitiativeSpendModel.Phase.Ready -> {
                    HomeCard("spend-summary") {
                        Text(stringResource(R.string.eng_total_spent), style = MaterialTheme.typography.labelLarge)
                        Text(phase.spend.totalLabel, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("spend-total"))
                    }
                    if (phase.spend.categories.isEmpty()) Text(stringResource(R.string.eng_no_spending_by_category_yet), modifier = Modifier.testTag("spend-empty"))
                    phase.spend.categories.forEachIndexed { index, category ->
                        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("spend-row-$index"), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(category.category, modifier = Modifier.weight(1f))
                            Text(category.amountLabel)
                        }
                    }
                    Text(stringResource(R.string.eng_totals_by_category_not_individual), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
