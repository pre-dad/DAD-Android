package org.dad.foundation

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class GiftHistorySummaryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun aProductionSummaryIsNeverLabelledAsASandboxRecord() {
        val service = object : DashboardService {
            override suspend fun dashboard(): DashboardState = error("not used")
            override suspend fun todayInitiative(): HomeInitiative? = error("not used")
            override suspend fun gifts(offset: Int) = GiftHistoryPage(emptyList(), null)
            override suspend fun receipt(giftId: String): HomeReceipt = error("not used")
            override suspend fun annualSummary(year: Int) = AnnualGivingSummary(year, 1, 3000, 0, 3000, taxReceipt = true)
        }
        val model = GiftHistoryModel(service, year = 2026, showPriorYear = false)
        compose.setContent { GiftHistoryScreen(model, onClose = {}, onSignedOut = {}) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("history-year-total")).fetchSemanticsNodes().isNotEmpty() }
        val sandboxNotice = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.home_sandbox_records_not_tax_receipts)
        compose.onNodeWithText(sandboxNotice).assertDoesNotExist()
    }
}
