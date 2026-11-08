package org.dad.foundation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Donor home reads (PRD S-1..S-4, A-1..A-5, I-1..I-3, gift history). Bound to [DashboardModel] and
// [GiftHistoryModel]; every state the server can produce has its own on-screen answer, and state is
// always carried in text as well as colour. Every control is at least 48dp and nothing is clipped at
// large font scales (the content scrolls).

// US long dates everywhere on Home ("April 22, 2027"), not the shorter MEDIUM form ("Apr 22, 2027").
private val longDate: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(java.util.Locale.US)

/** "2025-01-02" -> "January 2, 2025"; anything unparseable is shown as sent rather than dropped. */
internal fun friendlyDate(value: String): String =
    try { LocalDate.parse(value).format(longDate) } catch (_: java.time.format.DateTimeParseException) { value }

private fun friendlyDate(value: OffsetDateTime): String = value.toLocalDate().format(longDate)

@Composable
internal fun HomeCard(tag: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(tag),
        // Card radius comes from the token via MaterialTheme.shapes.medium (radius("card") = 18dp),
        // so the old takeIf{>0} fallback is no longer needed.
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, DadTheme.color("line")),
        tonalElevation = 1.dp,
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() } }
}

/** The dashboard cards, today's initiative and the way into gift history. */
@Composable
fun DonorHome(
    model: DashboardModel, giving: GivingModel? = null, onOpenGift: () -> Unit = {}, onOpenEvents: (() -> Unit)? = null,
    onOpenEngagement: (() -> Unit)? = null, onOpenSpend: ((String, String) -> Unit)? = null,
    onOpenHistory: () -> Unit, onSignedOut: () -> Unit,
    milestones: MilestoneStore? = null, onShareMilestone: ((String) -> Unit)? = null,
) {
    LaunchedEffect(Unit) { model.load(); giving?.load() }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    Column(Modifier.fillMaxWidth().testTag("donor-home"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // KEEP EXACTLY: the failed-refresh notice over stale cards, the loading skeleton, the Preparing
        // card, the first-load Failed state and its retry. Only the Ready dashboard is re-ordered/restyled.
        model.refreshNotice?.let {
            Text(it.asString(), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("dashboard-notice").semantics { liveRegion = LiveRegionMode.Polite })
        }
        when (val phase = model.phase) {
            DashboardModel.Phase.Loading -> DashboardSkeleton()
            is DashboardModel.Phase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(phase.message.asString(), modifier = Modifier.testTag("dashboard-error"))
                Button(onClick = { model.load() }, modifier = Modifier.heightIn(min = 48.dp).testTag("dashboard-retry")) { Text(stringResource(R.string.common_try_again)) }
            }
            is DashboardModel.Phase.Ready -> when (val state = phase.state) {
                DashboardState.Preparing -> HomeCard("dashboard-preparing") {
                    Text(stringResource(R.string.home_getting_your_dashboard_ready), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_your_numbers_appear_here_once_your), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { model.load() }, modifier = Modifier.heightIn(min = 48.dp).testTag("dashboard-refresh")) { Text(stringResource(R.string.common_refresh)) }
                }
                // PRD-C order: (1) at-risk banner, (2) streak hero, (3) Link|IRL tiles — rendered inside
                // DashboardCards in that order, with the initiative and the monthly-gift item below.
                is DashboardState.Ready -> DashboardCards(state.dashboard, onOpenGift = onOpenGift, milestones = milestones, onShareMilestone = onShareMilestone)
            }
        }
        // (4) Today's initiative.
        InitiativeCard(model.initiative, onRetry = { model.load() }, onSpend = onOpenSpend)
        // (5) Your monthly gift — DadGiftFraming at equal weight; the row opens Giving. Carries open-gift
        // (moved off GiftSummaryCard's "Manage gift") and keeps gift-summary / gift-summary-card on the live
        // state line so the row still reflects the donor's real gift (GiftFlowTest pins that text).
        giving?.let { MonthlyGiftItem(it, onOpenGift) }
        if (onOpenEngagement != null) {
            HomeCard("engagement-card") {
                Text(stringResource(R.string.home_badges_rankings_network), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
                Text(stringResource(R.string.home_your_badges_standing_referral), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onOpenEngagement, modifier = Modifier.heightIn(min = 48.dp).testTag("open-engagement")) { Text(stringResource(R.string.home_see_your_impact)) }
            }
        }
        if (onOpenEvents != null) OutlinedButton(onClick = onOpenEvents, modifier = Modifier.heightIn(min = 48.dp).testTag("open-events")) { Text(stringResource(R.string.home_events_volunteering)) }
        OutlinedButton(onClick = onOpenHistory, modifier = Modifier.heightIn(min = 48.dp).testTag("open-history")) { Text(stringResource(R.string.common_gift_history)) }
    }
}

/** "Your monthly gift" row: the $30/month · $1 a day framing at equal weight ([DadGiftFraming]), the live
 *  gift-state line (so the row tracks the donor's real gift), and an open-Giving tap.
 *  Carries `gift-summary-card` on the card, `gift-summary` on the state line and `open-gift` on the row. */
@Composable
private fun MonthlyGiftItem(giving: GivingModel, onOpenGift: () -> Unit) {
    // giving.load() is driven by DonorHome's top-level LaunchedEffect, matching the old GiftSummaryCard.
    HomeCard("gift-summary-card") {
        Text(stringResource(R.string.home_your_monthly_gift), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        // The tap target carries `open-gift` and is a MERGING clickable, so `gift-summary` must stay a
        // sibling OUTSIDE it: a testTag nested under a merging clickable folds into the parent and stops
        // resolving as its own node (GiftFlowTest addresses `gift-summary` and `open-gift` separately).
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = stringResource(R.string.home_open_your_monthly_gift), role = Role.Button, onClick = onOpenGift)
                .testTag("open-gift"),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DadGiftFraming(stringResource(R.string.common_30_month), stringResource(R.string.common_1_a_day))
        }
        // The live state line keeps the gift-summary text that GiftFlowTest tracks on Home.
        Text(
            summaryLine(giving),
            style = MaterialTheme.typography.bodyMedium,
            color = DadTheme.color("dkmut"),
            modifier = Modifier.testTag("gift-summary"),
        )
    }
}

/**
 * What stands in for the dashboard cards while they load. It lays out the same cards in the same type styles as
 * the real ones (dimmed, with a spinner over the top), so when the data lands nothing below it — the gift card, the
 * initiative and the "Gift history" button — jumps under the donor's thumb. The text is decoration, not content:
 * it is hidden from accessibility, which hears one "Loading your dashboard" instead.
 */
@Composable
private fun DashboardSkeleton() {
    Box(
        Modifier.fillMaxWidth().testTag("dashboard-loading").contentDescribed(R.string.home_loading_your_dashboard),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth().alpha(0.35f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            HomeCard("skeleton-streak") {
                Text(stringResource(R.string.home_founding_donor), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.home_skeleton_streak_headline), style = MaterialTheme.typography.headlineMedium)
                DadStreakBar(filled = 0)
                Text(stringResource(R.string.home_skeleton_next_milestone), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.home_skeleton_national_rank), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.home_skeleton_city_rank), style = MaterialTheme.typography.bodyMedium)
            }
            HomeCard("skeleton-impact") {
                Text(stringResource(R.string.home_total_given), style = MaterialTheme.typography.labelLarge)
                Text("$0,000", style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.home_skeleton_impact_counts), style = MaterialTheme.typography.bodyMedium)
            }
            HomeCard("skeleton-network") {
                Text(stringResource(R.string.common_your_network), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.home_skeleton_network_funds))
                Text(stringResource(R.string.home_skeleton_active_donors), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.home_skeleton_network_joined), style = MaterialTheme.typography.bodyMedium)
            }
        }
        CircularProgressIndicator()
    }
}

/** The initiative card's loading body, shaped like its loaded one for the same reason as [DashboardSkeleton]. */
@Composable
private fun InitiativeSkeleton() {
    Box(
        Modifier.fillMaxWidth().testTag("initiative-loading").contentDescribed(R.string.home_loading_todays_initiative),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth().alpha(0.35f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.home_todays_initiative), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.home_skeleton_initiative_meta), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.home_skeleton_initiative_summary))
            repeat(3) {
                Column {
                    Text(stringResource(R.string.home_skeleton_metric_value), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.home_skeleton_metric_label), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(HomeInitiative.ATTRIBUTION_NOTE.asString(), style = MaterialTheme.typography.bodySmall)
        }
        CircularProgressIndicator()
    }
}

@Composable
private fun DashboardCards(dashboard: HomeDashboard, onOpenGift: () -> Unit, milestones: MilestoneStore?, onShareMilestone: ((String) -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // (1) At-risk banner — ONLY when the streak is at risk. Colour is never the only signal: a
        // warning glyph (with a contentDescription) + text + the warn-wash token background all carry it.
        val streak = dashboard.streak
        if (streak?.atRisk == true) {
            val by = streak.graceEndsAt?.let { friendlyDate(it) }
            AtRiskBanner(by, onOpenGift)
        }
        // (2) Streak hero card on ink.
        StreakHeroCard(dashboard)
        // The baseline is seeded the first time this runs, so existing donors never see a card for a
        // milestone they reached before it existed. Dismissal is remembered per milestone in the store.
        if (milestones != null) {
            val context = LocalContext.current
            val pending = remember(streak, milestones) { pendingMilestone(streak, milestones) }
            var dismissed by rememberSaveable(pending) { mutableStateOf(false) }
            if (pending != null && !dismissed) {
                MilestoneCard(
                    headline = milestoneLine(pending),
                    onDismiss = { milestones.markSeen(pending); dismissed = true },
                    onShare = onShareMilestone?.let { share -> { share(milestoneLine(pending).resolve(context)) } },
                )
            }
        }
        // (3) Link | IRL tiles side by side, equal weight. A null referrals block means the figures are
        // unknown — the tiles show "—"/"Not available", never 0.
        HomeCard("impact-card") {
            Text(stringResource(R.string.home_your_invites), style = MaterialTheme.typography.labelLarge)
            val referrals = dashboard.referrals
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ReferralTile(
                    title = stringResource(R.string.home_link), count = referrals?.link, modifier = Modifier.weight(1f),
                )
                ReferralTile(
                    title = stringResource(R.string.home_irl), count = referrals?.irl, modifier = Modifier.weight(1f),
                )
            }
            // Keep the existing "Total given" line and its dash-given tag (DashboardFlowTest pins it; "--"
            // for unknown comes from ImpactTotals.givenLabel, never a 0).
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.home_total_given), style = MaterialTheme.typography.labelLarge)
            Text(dashboard.impact.givenLabel, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("dash-given"))
            val funded = dashboard.impact.initiativesFunded
            val events = dashboard.impact.eventsVolunteered
            Text(
                stringResource(
                    R.string.common_pair_dot,
                    pluralStringResource(R.plurals.home_initiatives_funded, funded, funded),
                    pluralStringResource(R.plurals.home_events_volunteered, events, events),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        dashboard.network?.let { network ->
            HomeCard("network-card") {
                Text(stringResource(R.string.common_your_network), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
                Text(stringResource(R.string.home_network_funds_per_year, network.annualizedLabel), modifier = Modifier.testTag("network-annualized"))
                Text(pluralStringResource(R.plurals.home_active_donors, network.activeCount.toQuantity(), network.activeCount), style = MaterialTheme.typography.bodyMedium)
                dashboard.referrals?.let { Text(pluralStringResource(R.plurals.home_joined_through_you_given, it.joined.toQuantity(), it.joined, it.totalLabel), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** A Link/IRL referral count as a [DadStatTile]. Unknown ([count] null) stays unknown (the tile's "—");
 *  a real 0 reads muted with a one-line hint rather than as an achievement. */
@Composable
private fun ReferralTile(title: String, count: Long?, modifier: Modifier = Modifier) {
    val caption = when {
        count == null -> stringResource(R.string.common_not_available)
        count == 0L -> stringResource(R.string.home_no_invites_yet)
        else -> pluralStringResource(R.plurals.home_referrals_joined, count.toQuantity(), count)
    }
    // count==null → pass null so the tile renders "—"; a real 0 shows "0" with the muted hint caption.
    DadStatTile(title = title, value = count?.toString(), caption = caption, modifier = modifier)
}

/**
 * A dismissible celebration of a newly crossed streak milestone. Not a dialog and not clickable as a
 * whole, so the headline and both buttons resolve as their own nodes. Its haptic fires once when the
 * card first enters, as a response to the milestone being reached.
 */
@Composable
internal fun MilestoneCard(headline: UiText, onDismiss: () -> Unit, onShare: (() -> Unit)?) {
    val haptics = rememberDadHaptics()
    LaunchedEffect(Unit) { haptics.confirm() }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("milestone-card"),
        shape = MaterialTheme.shapes.medium,
        color = DadTheme.color("ok-wash"),
        contentColor = DadTheme.color("ink"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                headline.asString(), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DadTextButton(stringResource(R.string.home_dismiss), Modifier.testTag("milestone-dismiss"), contentColor = DadTheme.color("ink"), onClick = onDismiss)
                if (onShare != null) {
                    DadSecondaryButton(stringResource(R.string.common_share), Modifier.testTag("milestone-share"), contentColor = DadTheme.color("ink"), onClick = onShare)
                }
            }
        }
    }
}

/** The at-risk banner (S-1): a warn-wash block with a warning glyph, the renewal deadline, and an
 *  "Update payment" text action that switches to Giving. Carries `streak-at-risk`. */
@Composable
private fun AtRiskBanner(byDate: String?, onOpenGift: () -> Unit) {
    val line = if (byDate != null) stringResource(R.string.home_streak_needs_card_by, byDate) else stringResource(R.string.home_your_streak_needs_a_new_card)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DadTheme.color("warn-wash"), RoundedCornerShape(DadTheme.radius("card")))
            .padding(16.dp)
            .testTag("streak-at-risk")
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DadSymbol("warning", contentDescription = stringResource(R.string.common_warning), tint = DadTheme.color("warn"))
            Text(line, style = MaterialTheme.typography.bodyMedium, color = DadTheme.color("warn"))
        }
        DadTextButton(stringResource(R.string.home_update_payment), onClick = onOpenGift)
    }
}

/** The streak hero on the ink surface: the "Giving streak" label, the day count in display type, the
 *  teal 30-day bar, the milestone + rank lines, and the Founding Donor chip when earned. */
@Composable
private fun StreakHeroCard(dashboard: HomeDashboard) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("streak-card"),
        shape = MaterialTheme.shapes.medium,
        color = DadTheme.color("ink"),
        contentColor = DadTheme.color("paper"),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dashboard.foundingDonor) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    // mergeDescendants so the "Founding donor" child Text folds into the tagged node;
                    // onNodeWithTag("founding-donor").assertTextContains(...) queries the merged tree and
                    // a bare testTag Row (no merge) would expose no text.
                    modifier = Modifier.testTag("founding-donor").semantics(mergeDescendants = true) {},
                ) {
                    DadSymbol("star", contentDescription = stringResource(R.string.home_founding_donor_badge), tint = DadTheme.color("brand"))
                    Text(stringResource(R.string.home_founding_donor), style = MaterialTheme.typography.labelLarge, color = DadTheme.brandAccent(dark = true))
                }
            }
            Text(stringResource(R.string.home_giving_streak), style = MaterialTheme.typography.labelLarge, color = DadTheme.color("dkmut"))
            val streak = dashboard.streak
            if (streak != null) {
                Text(streak.headline.asString(), style = MaterialTheme.typography.displayMedium, modifier = Modifier.dadHeading().testTag("streak-count"))
                streak.ladder?.let { ladder ->
                    DadStreakBar(filled = ladder.bar.count { it }, total = ladder.bar.size.coerceAtLeast(1))
                    ladder.nextLabel?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("streak-next"), color = DadTheme.color("dkmut")) }
                }
            } else {
                // The server says the figure is not known: say so rather than show a zero streak.
                Text(stringResource(R.string.home_your_streak_isnt_available_right), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("streak-unknown"), color = DadTheme.color("paper"))
            }
            when (val rank = dashboard.rank) {
                is RankLine.Ranked -> {
                    rank.standing.nationalLabel?.let { Text(it.asString(), style = MaterialTheme.typography.titleSmall, modifier = Modifier.testTag("rank-national"), color = DadTheme.color("paper")) }
                    rank.standing.cityLabel?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("rank-city"), color = DadTheme.color("dkmut")) }
                }
                RankLine.NotRankedYet -> Text(stringResource(R.string.common_not_ranked_yet), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("rank-unranked"), color = DadTheme.color("dkmut"))
                RankLine.OptedOut -> Unit // S-4: a hidden donor shows no rank line at all
            }
        }
    }
}

@Composable
private fun InitiativeCard(phase: DashboardModel.InitiativePhase, onRetry: () -> Unit, onSpend: ((String, String) -> Unit)? = null) {
    HomeCard("initiative-card") {
        Text(stringResource(R.string.home_today_your_1_funded), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
        when (phase) {
            DashboardModel.InitiativePhase.Loading -> InitiativeSkeleton()
            DashboardModel.InitiativePhase.None ->
                Text(stringResource(R.string.home_nothing_is_in_todays_rotation_yet), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("initiative-none"))
            DashboardModel.InitiativePhase.Failed -> {
                Text(stringResource(R.string.home_todays_initiative_isnt_available), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("initiative-error"))
                OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("initiative-retry")) { Text(stringResource(R.string.common_try_again)) }
            }
            is DashboardModel.InitiativePhase.Ready -> {
                val initiative = phase.initiative
                Text(initiative.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("initiative-title"))
                Text(stringResource(R.string.home_initiative_meta, initiative.location, initiative.kind.lowercase().replaceFirstChar { it.uppercase() }, friendlyDate(initiative.startedOn)), style = MaterialTheme.typography.bodyMedium)
                if (initiative.summary.isNotEmpty()) Text(initiative.summary)
                initiative.headlineMetrics.forEach { metric ->
                    Column(Modifier.semantics(mergeDescendants = true) {}) {
                        Text(metric.valueLabel.asString(), style = MaterialTheme.typography.titleMedium)
                        Text(metric.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(HomeInitiative.ATTRIBUTION_NOTE.asString(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("initiative-note"))
                if (onSpend != null) {
                    OutlinedButton(onClick = { onSpend(initiative.id, initiative.title) }, modifier = Modifier.heightIn(min = 48.dp).testTag("open-spend")) {
                        Text(stringResource(R.string.common_where_the_money_went))
                    }
                }
            }
        }
    }
}

/** Gift history (paged) with an inline gift record per row and the calendar-year total on top. */
@Composable
fun GiftHistoryScreen(model: GiftHistoryModel, onClose: () -> Unit, onSignedOut: () -> Unit) {
    LaunchedEffect(Unit) { model.load() }
    DisposableEffect(Unit) { onDispose { model.cancel() } }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    LazyColumn(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp).testTag("history-screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.common_gift_history), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
                listOf("" to model.summary, "-prior" to model.priorSummary).forEach { (suffix, summary) ->
                    if (summary == null) return@forEach
                    HomeCard("history-summary$suffix") {
                        Text(stringResource(R.string.home_year_total, summary.year), style = MaterialTheme.typography.labelLarge)
                        Text(summary.netLabel, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("history-year-total$suffix"))
                        Text(stringResource(R.string.home_sandbox_records_not_tax_receipts), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        when (val phase = model.phase) {
            GiftHistoryModel.Phase.Loading -> item { CircularProgressIndicator(Modifier.testTag("history-loading").contentDescribed(R.string.home_loading_your_gifts)) }
            is GiftHistoryModel.Phase.Failed -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(phase.message.asString(), modifier = Modifier.testTag("history-error"))
                    Button(onClick = { model.load() }, modifier = Modifier.heightIn(min = 48.dp).testTag("history-retry")) { Text(stringResource(R.string.common_try_again)) }
                }
            }
            GiftHistoryModel.Phase.Ready ->
                if (model.gifts.isEmpty()) item { Text(stringResource(R.string.home_no_gifts_yet_once_you_give_each), modifier = Modifier.testTag("history-empty")) }
                else {
                    itemsIndexed(model.gifts, key = { _, gift -> gift.id }) { index, gift -> GiftRow(model, gift, index) }
                    item { HistoryFooter(model) }
                }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun HistoryFooter(model: GiftHistoryModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model.isLoadingMore) CircularProgressIndicator(Modifier.testTag("history-more-loading").contentDescribed(R.string.home_loading_more_gifts))
        model.moreError?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("history-more-error")) }
        if (model.hasMore && !model.isLoadingMore) {
            OutlinedButton(onClick = { model.requestMore() }, modifier = Modifier.heightIn(min = 48.dp).testTag("history-more")) {
                Text(if (model.moreError == null) stringResource(R.string.home_load_more) else stringResource(R.string.common_try_again))
            }
        }
        if (model.reachedLimit) Text(stringResource(R.string.home_showing_most_recent_gifts, GiftHistoryModel.MAX_ROWS), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("history-limit"))
    }
}

@Composable
private fun GiftRow(model: GiftHistoryModel, gift: HomeGift, index: Int) {
    val open = model.receipt?.giftId == gift.id
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = if (open) stringResource(R.string.home_hide_the_gift_record) else stringResource(R.string.home_show_the_gift_record), role = Role.Button) {
                    if (open) model.hideReceipt() else model.showReceipt(gift.id)
                }
                .testTag("gift-row-$index"),
        ) {
            Text(friendlyDate(gift.paidAt), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.common_pair_dot, gift.frequencyLabel.asString(), gift.netLabel), style = MaterialTheme.typography.bodyLarge)
            gift.refundLabel?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
        }
        if (open) ReceiptDetail(model.receipt)
    }
}

@Composable
private fun ReceiptDetail(phase: GiftHistoryModel.ReceiptPhase?) {
    when (phase) {
        is GiftHistoryModel.ReceiptPhase.Loading -> CircularProgressIndicator(Modifier.testTag("receipt-loading").contentDescribed(R.string.home_loading_the_gift_record))
        is GiftHistoryModel.ReceiptPhase.Failed -> Text(phase.message.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("receipt-error"))
        is GiftHistoryModel.ReceiptPhase.Ready -> {
            val receipt = phase.receipt
            HomeCard("receipt-detail") {
                Text(stringResource(R.string.home_gift_record), style = MaterialTheme.typography.titleSmall, modifier = Modifier.dadHeading())
                Text(stringResource(R.string.home_receipt_amount, receipt.gift.amountLabel))
                if (receipt.gift.refundedMinor > 0) Text(stringResource(R.string.home_receipt_refunded, formatMinorUsd(receipt.gift.refundedMinor)))
                Text(stringResource(R.string.home_receipt_net, receipt.gift.netLabel))
                receipt.refunds.forEach { Text(stringResource(R.string.home_receipt_refund_line, formatMinorUsd(it.amountMinor), it.status.replaceFirstChar { c -> c.uppercase() }), style = MaterialTheme.typography.bodySmall) }
                if (!receipt.taxReceipt) Text(stringResource(R.string.home_this_is_a_sandbox_record_not_a_tax), style = MaterialTheme.typography.bodySmall)
            }
        }
        null -> Unit
    }
}
