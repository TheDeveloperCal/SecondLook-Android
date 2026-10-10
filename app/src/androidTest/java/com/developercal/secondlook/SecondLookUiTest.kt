package com.developercal.secondlook

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecondLookUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun dashboardAndMessageAnalysisRender() {
        compose.onNodeWithText("Take a second look.").assertIsDisplayed()
        compose.onAllNodesWithText("Check a message")[0].performClick()
        compose.onNodeWithText("Message or email").assertIsDisplayed()
        compose.onNodeWithText("Message or email").performTextInput("Your account will be closed today. Act now. Send the verification code.")
        compose.onNodeWithText("Analyze on this device").performClick()
        compose.onNodeWithText("High-risk indicators found").assertIsDisplayed()
        compose.onNodeWithText("A code or credential is requested").assertIsDisplayed()
        compose.onNodeWithText("Share report").assertIsDisplayed()
    }

    @Test fun bottomNavigationOpensLinkAndCallFlows() {
        compose.onNodeWithText("Link", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Inspect address").assertIsDisplayed()
        compose.onNodeWithText("Call", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Talk through a call").assertIsDisplayed()
        compose.onNodeWithText("Caller ID can be spoofed. A familiar-looking number does not prove who called.").assertIsDisplayed()
    }
}
