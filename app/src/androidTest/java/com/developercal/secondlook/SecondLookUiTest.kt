package com.developercal.secondlook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.assertTextContains
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecondLookUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var priorHistorySetting: Boolean? = null

    @Before fun keepExistingHistorySettingAndAvoidSavingTestContent() {
        compose.activityRule.scenario.onActivity { activity ->
            val settings = (activity.application as SecondLookApp).settings
            priorHistorySetting = settings.historyEnabled
            settings.historyEnabled = false
        }
    }

    @After fun restoreExistingHistorySettingWithoutClearingRecords() {
        val previous = priorHistorySetting ?: return
        compose.activityRule.scenario.onActivity { activity ->
            (activity.application as SecondLookApp).settings.historyEnabled = previous
        }
    }

    @Test fun dashboardAndMessageAnalysisRender() {
        compose.onNodeWithText("Take a second look.").assertIsDisplayed()
        compose.onAllNodesWithText("Check a message")[0].performClick()
        compose.onNodeWithText("Message or email").assertIsDisplayed()
        compose.onNodeWithText("Message or email").performTextInput("Your account will be closed today. Act now. Send the verification code.")
        compose.onNodeWithText("Analyze on this device").performClick()
        compose.onNodeWithText("Message check").assertIsDisplayed()
        compose.onNodeWithText("High-risk indicators found").assertIsDisplayed()
        compose.onNodeWithText("A code or credential is requested").assertIsDisplayed()
        compose.onNodeWithText("Share report").performScrollTo().assertIsDisplayed()
    }

    @Test fun savedChecksBackReturnsToSettings() {
        compose.activityRule.scenario.onActivity { activity -> (activity.application as SecondLookApp).settings.historyEnabled = true }
        compose.onNodeWithText("Settings", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Save results on this device").assertIsDisplayed()
        compose.onNodeWithText("View saved results", substring = true).performClick()
        compose.onNodeWithText("Saved checks").assertIsDisplayed()
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.onNodeWithText("Settings & privacy").assertIsDisplayed()
        compose.onNodeWithText("Save results on this device").assertIsDisplayed()
    }

    @Test fun bottomNavigationOpensLinkAndCallFlows() {
        compose.onNodeWithText("Link", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Inspect address").assertIsDisplayed()
        compose.onNodeWithText("Call", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Talk through a call").assertIsDisplayed()
        compose.onNodeWithText("Caller ID can be spoofed. A familiar-looking number does not prove who called.").assertIsDisplayed()
    }

    @Test fun sharedTextIsPrefilledWithoutAutomaticAnalysis() {
        val sample = "Your parcel is held. Pay a delivery fee at example.invalid"
        compose.activityRule.scenario.onActivity { activity ->
            activity.prefillSharedMessage(sample)
        }
        compose.waitForIdle()
        compose.onNodeWithText("Check a message").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains("Your parcel", substring = true)
        compose.onNodeWithText("Analyze on this device").assertIsDisplayed()
        compose.onNodeWithText("Message check").assertDoesNotExist()
    }

    @Test fun numberLookupExplainsItsCacheStateWithoutClaimingSafety() {
        compose.onNodeWithText("Check a phone number").performClick()
        compose.onNodeWithText("Phone number").performTextInput("+1 (202) 555-0123")
        compose.onNodeWithText("Look up locally").performClick()
        compose.waitForIdle()
        val hasNoSnapshotMessage = compose.onAllNodesWithText("No source snapshot is cached").fetchSemanticsNodes().isNotEmpty()
        if (hasNoSnapshotMessage) {
            compose.onNodeWithText("No source snapshot is cached").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("No result here verifies a number.", substring = true).performScrollTo().assertIsDisplayed()
        } else {
            compose.onNodeWithText("FTC Do Not Call reported calls", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Unverified consumer reports", substring = true).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("Choose calling app").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Choose messaging app").performScrollTo().assertIsDisplayed()
    }
}
