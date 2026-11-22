package org.dad.foundation

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class GiftHistorySummaryTest {
    @get:Rule val compose = createComposeRule()

    private fun showSummary(taxReceipt: Boolean) {
        val service = object : DashboardService {
            override suspend fun dashboard(): DashboardState = error("not used")
            override suspend fun todayInitiative(): HomeInitiative? = error("not used")
            override suspend fun gifts(offset: Int) = GiftHistoryPage(emptyList(), null)
            override suspend fun receipt(giftId: String): HomeReceipt = error("not used")
            override suspend fun annualSummary(year: Int) = AnnualGivingSummary(year, 1, 3000, 0, 3000, taxReceipt = taxReceipt)
        }
        val model = GiftHistoryModel(service, year = 2026, showPriorYear = false)
        compose.setContent { GiftHistoryScreen(model, onSignedOut = {}) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("history-year-total")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("history-year-total").assertTextEquals("$30")
    }

    private val sandboxNotice
        get() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.home_sandbox_records_not_tax_receipts)

    @Test fun aProductionSummaryIsNeverLabelledAsASandboxRecord() {
        showSummary(taxReceipt = true)
        compose.onNodeWithText(sandboxNotice).assertDoesNotExist()
    }

    @Test fun aSandboxSummaryIsLabelledAsNotATaxReceipt() {
        showSummary(taxReceipt = false)
        compose.onNodeWithText(sandboxNotice).assertExists()
    }
}
