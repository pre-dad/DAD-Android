package org.dad.foundation

import java.util.Locale

// Donor impact, spend and rankings (PRD A-*, I-*, K-*). Pure value types and the display / eligibility
// rules live here so rankings opt-out and money formatting are unit-testable without a network or UI.

/** Format USD minor units (cents) for display: "$1,240" or "$12.34". Long-safe for large totals. */
internal fun formatMinorUsd(minor: Long): String {
    if (minor % 100L == 0L) return String.format(Locale.US, "$%,d", minor / 100L)
    return String.format(Locale.US, "$%,.2f", minor / 100.0)
}

// MARK: - Impact totals (I-1..I-3)

/**
 * Lifetime impact. A null [givenMinor] means "not currently known" — the UI must render it as "--",
 * never as $0 (the contract's explicit rule).
 */
data class ImpactTotals(
    val givenMinor: Long?,
    val initiativesFunded: Int,
    val eventsVolunteered: Int,
) {
    /** "$1,240" or "--" when unknown — never "$0" for an unknown figure. */
    val givenLabel: String get() = givenMinor?.let { formatMinorUsd(it) } ?: "--"
}

// MARK: - Rankings (K-1..K-3), opt-out hides self

/**
 * National/city standing. [optedOut] mirrors the explicit `rankings_opted_out` flag — read from the donor
 * dashboard (contract v2.3.0) wherever the dashboard is the source, and the profile otherwise. It is NOT inferred from missing figures, because a donor can be unranked
 * (null standing) without having opted out. When opted out no position is ever shown, and the UI
 * must never render a name list.
 */
data class RankingStanding(
    val nationalPercentile: Int?,
    val city: String?,
    val cityRank: Int?,
    val optedOut: Boolean = false,
) {
    val isOptedOut: Boolean get() = optedOut

    companion object {
        /** The standing of a donor who set `rankings_opted_out`: nothing is shown. */
        fun optedOut() = RankingStanding(null, null, null, optedOut = true)

        /**
         * Builds the standing from the dashboard's fields. [rankingsOptedOut] is the dashboard's
         * explicit flag: true hides every figure, false with null figures means opted in but not
         * yet ranked.
         */
        fun fromDashboard(nationalPercentile: Int?, city: String?, cityRank: Int?, rankingsOptedOut: Boolean) =
            if (rankingsOptedOut) optedOut() else RankingStanding(nationalPercentile, city, cityRank)
    }

    /**
     * "Top 2% nationally". [nationalPercentile] is the server's top-percentile bucket (the contract's
     * `national_percentile`: 1 = top 1%, 2 = top 2%), so it is shown as-is, never inverted as `100 - x`.
     * Clamped to 1..100 (never "Top 0%" or "Top 120%"); null when opted out / unknown.
     */
    val nationalLabel: UiText? get() = nationalPercentile?.takeUnless { optedOut }?.let { UiText.res(R.string.eng_top_percent_nationally, it.coerceIn(1, 100)) }

    /** "#4 in Austin", or null when opted out / unknown. */
    val cityLabel: UiText? get() {
        if (optedOut) return null
        val c = city ?: return null
        val rank = cityRank ?: return null
        return UiText.res(R.string.eng_rank_in_city, rank, c)
    }
}

// MARK: - Spend-by-category (A-3), totals only

/** One spend category line (totals only — never per-transaction line items, PRD §5.2). */
data class SpendCategory(val category: String, val amountMinor: Long) {
    val amountLabel: String get() = formatMinorUsd(amountMinor)
}

/** The spend-by-category drill-in: totals by category (A-3), never a line-item ledger. */
data class InitiativeSpend(
    val totalMinor: Long,
    val categories: List<SpendCategory>,
) {
    val totalLabel: String get() = formatMinorUsd(totalMinor)
}
