package org.dad.foundation

import android.content.ActivityNotFoundException
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

// Giving screens (PRD G-1..G-11, D-5): the recurring gift, its amount, the payment method and the cancel
// path, bound to [GivingModel]. The app never handles card data: starting a gift and updating the card both
// open a Stripe-hosted page in a Custom Tab, and when the donor comes back the model re-reads the server
// until it reflects the outcome. Every confirmation is inline (no OS dialog), so it is deterministic to test.

/**
 * Opens the hosted page for the model's pending [GivingModel.handoff] in a Custom Tab, and tells the model
 * when the donor comes back to the app. Under the DEBUG stub backend ([stubComplete] non-null) the page is
 * an in-app stand-in instead, because the stub origin cannot load. Returns true while the stand-in covers
 * the screen.
 */
@Composable
internal fun hostedPage(model: GivingModel, stubComplete: ((HostedHandoff.Purpose) -> Unit)?): Boolean {
    val context = LocalContext.current
    var openedId by rememberSaveable { mutableLongStateOf(-1L) }
    val handoff = model.handoff
    if (handoff != null && stubComplete == null) {
        LaunchedEffect(handoff.id) {
            if (openedId != handoff.id) {
                openedId = handoff.id
                try {
                    CustomTabsIntent.Builder().build().launchUrl(context, handoff.url.toUri())
                } catch (_: ActivityNotFoundException) {
                    model.handoffUnavailable()
                }
            }
        }
    }
    // The Custom Tab covers this activity, so the donor coming back (close, back, or the return page's own
    // close button) is this activity resuming. The return page proves nothing: the model polls the server.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val pending = model.handoff
        if (pending != null && stubComplete == null && openedId == pending.id) model.handoffFinished()
    }
    if (handoff != null && stubComplete != null) {
        StubHostedPage(handoff.purpose, onComplete = { stubComplete(handoff.purpose); model.handoffFinished() }, onClose = { model.handoffFinished() })
        return true
    }
    return false
}

/** Stands in for the Stripe-hosted page under the DEBUG stub backend. */
@Composable
private fun StubHostedPage(purpose: HostedHandoff.Purpose, onComplete: () -> Unit, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("hosted-page-stub"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.give_secure_payment_page), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading())
        Text(if (purpose == HostedHandoff.Purpose.START_GIFT) stringResource(R.string.give_pretend_checkout_for_a_monthly) else stringResource(R.string.give_pretend_card_update))
        DadPrimaryButton(stringResource(R.string.give_complete_payment), Modifier.testTag("stub-complete")) { onComplete() }
        DadSecondaryButton(stringResource(R.string.give_close_without_paying), modifier = Modifier.testTag("stub-close")) { onClose() }
    }
}

/** The one-line gift state ("No monthly gift yet.", "$150/month · renews …", "…no further renewals",
 *  "Your gift isn’t available right now."). Shared with Home's monthly-gift row so both read identically. */
@Composable
internal fun summaryLine(model: GivingModel): String {
    if (!model.hasLoaded) return if (model.phase is GivingPhase.Failed) stringResource(R.string.give_your_gift_isnt_available_right_now) else stringResource(R.string.give_loading_your_gift)
    return when (val state = model.recurring) {
        RecurringState.None -> stringResource(R.string.give_no_monthly_gift_yet)
        RecurringState.Preparing -> stringResource(R.string.give_getting_your_first_gift_ready)
        is RecurringState.Active ->
            stringResource(
                if (state.status.needsPaymentAttention) R.string.give_summary_renews_attention else R.string.give_summary_renews,
                formatUsdMinor(state.amountMinor), shortDate(model.offer?.effectiveAt ?: state.periodEnd),
            )
        is RecurringState.Ending -> stringResource(R.string.give_summary_ends, formatUsdMinor(state.amountMinor), shortDate(state.stopsAt))
    }
}

/**
 * Set / change your recurring gift (G-1/G-2/G-7), start one (D-5), update the card, or cancel (G-8/G-9).
 * Preset chips plus an "Other" stepper ($1…$30/day) with the monthly ×30 echo; the cancel confirmation is
 * rendered INLINE (never an OS dialog or bottom sheet).
 */
@Composable
fun GiftScreen(model: GivingModel, stubComplete: ((HostedHandoff.Purpose) -> Unit)? = null, onClose: (() -> Unit)? = null, onSignedOut: () -> Unit = {}, onOpenHistory: (() -> Unit)? = null) {
    LaunchedEffect(Unit) { model.load() }
    LaunchedEffect(model.signedOut) { if (model.signedOut) onSignedOut() }
    if (hostedPage(model, stubComplete)) return
    val haptics = rememberDadHaptics()
    // Start from the current values so opening the screen on an existing state stays silent; only a
    // change made while the donor is here buzzes.
    var confirmedSeen by remember { mutableIntStateOf(model.confirmations) }
    LaunchedEffect(model.confirmations) {
        if (model.confirmations > confirmedSeen) haptics.confirm()
        confirmedSeen = model.confirmations
    }
    val needsAttention = (model.recurring as? RecurringState.Active)?.status?.needsPaymentAttention == true
    var attentionSeen by remember { mutableStateOf(needsAttention) }
    LaunchedEffect(needsAttention) {
        if (needsAttention && !attentionSeen) haptics.reject()
        attentionSeen = needsAttention
    }
    var confirmingCancel by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("gift-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            giftHeading(model.hasLoaded, model.recurring).asString(),
            style = MaterialTheme.typography.headlineMedium, modifier = Modifier.dadHeading(),
        )
        if (!model.hasLoaded) LoadingOrFailed(model) else Loaded(model, confirmingCancel, onOpenHistory) { confirmingCancel = it }
    }
}

@Composable
private fun LoadingOrFailed(model: GivingModel) {
    val phase = model.phase
    if (phase is GivingPhase.Failed) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(phase.message.asString(), modifier = Modifier.testTag("gift-error"))
            DadPrimaryButton(stringResource(R.string.common_try_again), Modifier.testTag("gift-retry")) { model.load() }
        }
    } else {
        Column(Modifier.testTag("gift-loading"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.give_loading_your_gift))
        }
    }
}

@Composable
private fun Loaded(model: GivingModel, confirmingCancel: Boolean, onOpenHistory: (() -> Unit)?, onConfirmingCancel: (Boolean) -> Unit) {
    Status(model)
    val state = model.recurring
    if (state is RecurringState.Active || state == RecurringState.None) AmountSection(model, state, confirmingCancel)
    if (state.subscriptionIdOrNull != null) PaymentSection(model)
    if (onOpenHistory != null) HistoryRow(onOpenHistory)
    Feedback(model)
    if (state is RecurringState.Active) CancelSection(model, state, confirmingCancel, onConfirmingCancel)
    // The app has no other signal that payments are not live.
    if (BuildConfig.DEBUG) Text(stringResource(R.string.give_preview_build_test_payments_only), style = MaterialTheme.typography.bodySmall, color = DadTheme.color("muted"))
}

@Composable
private fun Status(model: GivingModel) {
    when (val state = model.recurring) {
        RecurringState.None -> HomeCard("gift-none-card") {
            Text(stringResource(R.string.give_you_dont_have_a_monthly_gift_yet), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("gift-none"))
            Text(
                stringResource(R.string.give_choose_an_amount_below_youll),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        RecurringState.Preparing -> HomeCard("gift-preparing") {
            Text(stringResource(R.string.give_were_getting_your_first_gift_ready), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.give_it_can_take_a_moment_after), style = MaterialTheme.typography.bodySmall)
            DadSecondaryButton(stringResource(R.string.common_refresh), modifier = Modifier.testTag("gift-refresh"), enabled = !model.isBusy) { model.load() }
        }
        is RecurringState.Active, is RecurringState.Ending -> HomeCard("gift-status") {
            Text(stringResource(R.string.give_amount_per_month, formatUsdMinor(state.amountMinorOrNull ?: 0)), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("gift-monthly"))
            RenewalLines(model, state)
        }
    }
}

@Composable
private fun RenewalLines(model: GivingModel, state: RecurringState) {
    when (state) {
        is RecurringState.Active -> {
            val pending = model.offer?.pending
            val next = model.offer?.nextAmountMinor
            if (pending != null) {
                Text(
                    stringResource(
                        if (pending.confirmed) R.string.give_pending_change else R.string.give_pending_change_unconfirmed,
                        formatUsdMinor(pending.amountMinor), longUsDate(pending.effectiveAt),
                    ),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("gift-pending-change"),
                )
            } else if (next != null && next != state.amountMinor) {
                Text(
                    stringResource(R.string.give_next_renewal_on, formatUsdMinor(next), longUsDate(model.offer?.effectiveAt ?: state.periodEnd)),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("gift-renewal"),
                )
            } else {
                Text(stringResource(R.string.give_renews_on, longUsDate(model.offer?.effectiveAt ?: state.periodEnd)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("gift-renewal"))
            }
            if (state.status.needsPaymentAttention) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    DadSymbol("warning", stringResource(R.string.common_warning), tint = DadTheme.color("fail"))
                    Text(stringResource(R.string.give_your_last_renewal_didnt_go_through), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gift-payment-attention"))
                }
            }
            if (state.status == SubscriptionStatus.INCOMPLETE) {
                Text(stringResource(R.string.give_were_waiting_for_your_first), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gift-incomplete"))
            }
        }
        is RecurringState.Ending ->
            Text(stringResource(R.string.give_wont_renew_until, longUsDate(state.stopsAt)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gift-ending"))
        else -> Unit
    }
}

@Composable
private fun AmountSection(model: GivingModel, state: RecurringState, confirmingCancel: Boolean) {
    val startsGift = state == RecurringState.None
    HomeCard("gift-stepper") {
        GiftAmountChips(model.selection.dailyDollars, enabled = !model.isBusy, onSelect = { model.setDaily(it) })
        Text(stringResource(R.string.give_youll_give), style = MaterialTheme.typography.labelLarge)
        DadGiftFraming(
            monthly = stringResource(R.string.give_amount_per_month, formatUsdMinor(model.selection.monthlyMinor)),
            daily = dailyPhrase(model.selection.dailyDollars),
            monthlyModifier = Modifier.testTag("gift-selected-monthly"),
            dailyModifier = Modifier.testTag("gift-daily"),
        )
        if (state is RecurringState.Active && model.selection.monthlyMinor != state.amountMinor) {
            Text(
                stringResource(R.string.give_new_amount_starts, longUsDate(model.offer?.effectiveAt ?: state.periodEnd)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    // Hidden while the cancel confirmation is open so its "Keep giving" is the one filled primary.
    if (!confirmingCancel) {
        val monthly = formatUsdMinor(model.selection.monthlyMinor)
        if (startsGift) {
            DadPrimaryButton(stringResource(R.string.give_start_amount, monthly), Modifier.testTag("start-gift"), enabled = !model.isBusy) { model.startGift() }
        } else {
            DadPrimaryButton(stringResource(R.string.give_save_amount, monthly), Modifier.testTag("save-gift"), enabled = !model.isBusy) { model.applySelection() }
        }
    }
}

@Composable
private fun dailyPhrase(dollars: Int) = stringResource(R.string.give_dollars_a_day, dollars)

/** Preset chips (radio group) plus an "Other" chip that reveals a $1 stepper. Tapping Other changes no amount. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GiftAmountChips(dailyDollars: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    var otherPicked by rememberSaveable { mutableStateOf(false) }
    val chip = GiftPresets.selectedChip(dailyDollars, otherPicked)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GiftPresets.DAILY.forEach { dollars ->
                AmountChip(dailyPhrase(dollars), chip == GiftPresets.Chip.entries[GiftPresets.DAILY.indexOf(dollars)], enabled, "gift-chip-$dollars") {
                    otherPicked = false
                    onSelect(dollars)
                }
            }
            AmountChip(stringResource(R.string.common_other), chip == GiftPresets.Chip.OTHER, enabled, "gift-chip-other") { otherPicked = true }
        }
        if (chip == GiftPresets.Chip.OTHER) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StepButton("minus", stringResource(R.string.give_decrease_by_1_a_day), "gift-decrease", enabled && dailyDollars > GiftAmount.MIN_DAILY_DOLLARS) { onSelect(dailyDollars - 1) }
                StepButton("plus", stringResource(R.string.give_increase_by_1_a_day), "gift-increase", enabled && dailyDollars < GiftAmount.MAX_DAILY_DOLLARS) { onSelect(dailyDollars + 1) }
            }
        }
    }
}

@Composable
private fun AmountChip(label: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val shape = RoundedCornerShape(DadTheme.radius("pill"))
    val line = if (selected) "brand-ink" else if (dark) "ghost-line-dark" else "ghost-line"
    Box(
        Modifier
            .testTag(tag)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (selected) DadTheme.color("brand-wash") else Color.Transparent, shape)
            .border(if (selected) 2.dp else 1.dp, DadTheme.color(line), shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) DadTheme.color("ink") else Color.Unspecified)
    }
}

@Composable
private fun StepButton(symbol: String, description: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp).testTag(tag).semantics { contentDescription = description },
    ) {
        // The button carries the description; the glyph must not add a second label.
        Box(Modifier.clearAndSetSemantics {}) { DadSymbol(symbol, description) }
    }
}

@Composable
private fun PaymentSection(model: GivingModel) {
    // A plain Row, not a ListItem: ListItem always merges its descendants, which folds payment-status and the
    // update-payment button into one node.
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.clearAndSetSemantics {}) { DadSymbol("card", stringResource(R.string.give_card)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.give_payment_method), style = MaterialTheme.typography.titleMedium)
            Text(paymentLine(model.payment), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("payment-status"))
        }
        if (model.recurring is RecurringState.Active) {
            IconButton(
                onClick = { model.updatePayment() },
                enabled = !model.isBusy,
                modifier = Modifier.size(48.dp).testTag("update-payment").contentDescribed(R.string.give_update_payment_method),
            ) { Box(Modifier.clearAndSetSemantics {}) { DadSymbol("chevron", stringResource(R.string.give_update_payment_method)) } }
        }
    }
}

@Composable
private fun HistoryRow(onOpenHistory: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onOpenHistory).testTag("gift-open-history"),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Box(Modifier.clearAndSetSemantics {}) { DadSymbol("history", stringResource(R.string.give_history)) } },
        headlineContent = { Text(stringResource(R.string.common_gift_history), style = MaterialTheme.typography.titleMedium) },
        trailingContent = { Box(Modifier.clearAndSetSemantics {}) { DadSymbol("chevron", stringResource(R.string.give_open)) } },
    )
}

@Composable
private fun paymentLine(payment: PaymentMethodState?): String {
    val idle = stringResource(R.string.give_update_your_card_on_a_secure_page)
    val state = payment ?: return idle
    return when (state.kind) {
        PaymentMethodState.Kind.NONE -> idle
        PaymentMethodState.Kind.PENDING, PaymentMethodState.Kind.OPEN -> stringResource(R.string.give_a_card_update_is_in_progress)
        PaymentMethodState.Kind.APPLIED -> state.completedAt?.let { stringResource(R.string.give_card_updated_on, longUsDate(it)) } ?: stringResource(R.string.give_card_updated)
        PaymentMethodState.Kind.EXPIRED -> stringResource(R.string.give_the_last_card_update_expired_you)
        PaymentMethodState.Kind.CANCELLED -> stringResource(R.string.give_the_last_card_update_was_cancelled)
    }
}

@Composable
private fun Feedback(model: GivingModel) {
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (model.phase == GivingPhase.Working) CircularProgressIndicator(Modifier.testTag("gift-working"))
        if (model.isConfirming) {
            Column(Modifier.testTag("gift-confirming"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.give_confirming_with_the_payment))
            }
        }
        model.notice?.let { Text(it.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gift-notice")) }
        (model.phase as? GivingPhase.Failed)?.let { Text(it.message.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("gift-failure")) }
    }
}

@Composable
private fun CancelSection(model: GivingModel, active: RecurringState.Active, confirming: Boolean, onConfirming: (Boolean) -> Unit) {
    if (confirming) {
        Column(Modifier.testTag("cancel-confirm"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.give_cancel_your_gift), style = MaterialTheme.typography.titleMedium, modifier = Modifier.dadHeading())
            Text(CancelCopy.confirmation(active.periodEnd).asString())
            DadDestructiveTextButton(stringResource(R.string.give_end_my_gift), modifier = Modifier.testTag("cancel-end"), enabled = !model.isBusy) { model.cancel(); onConfirming(false) }
            DadPrimaryButton(stringResource(R.string.give_keep_giving), Modifier.testTag("cancel-keep")) { onConfirming(false) }
        }
    } else {
        DadDestructiveTextButton(stringResource(R.string.give_cancel_gift), modifier = Modifier.testTag("gift-cancel"), enabled = !model.isBusy) { onConfirming(true) }
    }
}
