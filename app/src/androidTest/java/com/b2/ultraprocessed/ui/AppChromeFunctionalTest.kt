package com.b2.ultraprocessed.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.b2.ultraprocessed.network.llm.ResultChatReply
import com.b2.ultraprocessed.ui.audio.AppSoundEvent
import com.b2.ultraprocessed.ui.theme.UltraProcessedTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class AppChromeFunctionalTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun scannerScreen_rendersSharedHeaderAndFooter_andRoutesHeaderActions() {
        var historyClicks = 0
        var settingsClicks = 0
        var barcodeClicks = 0

        composeRule.setContent {
            UltraProcessedTheme {
                ScannerScreen(
                    hasUsdaApiKey = false,
                    enableLiveCamera = false,
                    onScan = {},
                    onBarcodeScanned = { barcodeClicks += 1 },
                    onSettings = { settingsClicks += 1 },
                    onHistory = { historyClicks += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(AppTestTags.HEADER).assertIsDisplayed()
        composeRule.onNodeWithTag(AppTestTags.FOOTER).assertIsDisplayed()
        composeRule.onNodeWithText("Zest").assertIsDisplayed()
        composeRule.onNodeWithTag(AppTestTags.HEADER_ACTION_HISTORY).performClick()
        composeRule.onNodeWithTag(AppTestTags.HEADER_ACTION_SETTINGS).performClick()
        composeRule.onNodeWithTag(AppTestTags.SCANNER_BARCODE_BUTTON).performClick()

        composeRule.runOnIdle {
            assertEquals(1, historyClicks)
            assertEquals(1, settingsClicks)
            assertEquals(1, barcodeClicks)
        }
    }

    @Test
    fun resultsScreen_rendersSharedChrome() {
        composeRule.setContent {
            UltraProcessedTheme {
                ResultsScreen(
                    result = sampleScanResult,
                    onScanAgain = {},
                    onOpenHistory = {},
                    chatEnabled = false,
                    onAskAboutResult = { _, _ ->
                        Result.failure(IllegalStateException("Chat is disabled in this test."))
                    },
                )
            }
        }

        composeRule.onNodeWithTag(AppTestTags.HEADER).assertIsDisplayed()
        composeRule.onNodeWithTag(AppTestTags.FOOTER).assertIsDisplayed()
        composeRule.onNodeWithText("NOVA 4").assertIsDisplayed()
        composeRule.onNodeWithText("Uploaded Label Result").assertIsDisplayed()
    }

    @Test
    fun resultsScreen_novaTileDoesNotDuplicateNovaLabel() {
        composeRule.setContent {
            UltraProcessedTheme {
                ResultsScreen(
                    result = sampleScanResult.copy(novaGroup = 3),
                    onScanAgain = {},
                    onOpenHistory = {},
                    chatEnabled = false,
                    onAskAboutResult = { _, _ ->
                        Result.failure(IllegalStateException("Chat is disabled in this test."))
                    },
                )
            }
        }

        composeRule.onNodeWithText("NOVA 3").assertIsDisplayed()
        assertFalse(
            composeRule.onAllNodesWithText("NOVA 3 · NOVA 3")
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun resultsScreen_chatSendsMessageReceivesReplyAndPlaysSounds() {
        val sounds = mutableListOf<AppSoundEvent>()
        composeRule.setContent {
            UltraProcessedTheme {
                ResultsScreen(
                    result = sampleScanResult,
                    onScanAgain = {},
                    onOpenHistory = {},
                    chatEnabled = true,
                    onSoundEffect = { sounds += it },
                    onAskAboutResult = { _, _ ->
                        Result.success(
                            ResultChatReply(
                                allowed = true,
                                answer = "This scan is mostly about the ultra-processed marker.",
                                reason = "Allowed scan question.",
                            ),
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithTag(AppTestTags.RESULT_CHAT_INPUT).performTextInput("What is concerning?")
        composeRule.onNodeWithTag(AppTestTags.RESULT_CHAT_SEND).performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("This scan is mostly about the ultra-processed marker.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("What is concerning?").assertIsDisplayed()
        composeRule.onNodeWithText("This scan is mostly about the ultra-processed marker.").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(listOf(AppSoundEvent.Click, AppSoundEvent.Success), sounds)
        }
    }

    @Test
    fun disclaimerScreen_requiresAgreementBeforeNext() {
        var accepted = 0
        composeRule.setContent {
            UltraProcessedTheme {
                DisclaimerScreen(onAccepted = { accepted += 1 })
            }
        }

        composeRule.onNodeWithText("Disclaimer").assertIsDisplayed()
        composeRule.onNodeWithTag(AppTestTags.DISCLAIMER_NEXT).assertIsNotEnabled()
        composeRule.onNodeWithTag(AppTestTags.DISCLAIMER_AGREE).performClick()
        composeRule.onNodeWithTag(AppTestTags.DISCLAIMER_NEXT).assertIsEnabled()
        composeRule.onNodeWithTag(AppTestTags.DISCLAIMER_NEXT).performClick()

        composeRule.runOnIdle {
            assertEquals(1, accepted)
        }
    }

    private val sampleScanResult = ScanResultUi(
        productName = "Uploaded Label Result",
        novaGroup = 4,
        summary = "Flagged for multiple industrial additives and syrup-based sweeteners.",
        problemIngredients = listOf(
            ProblemIngredient(
                name = "High Fructose Corn Syrup",
                reason = "Industrial sweetener often seen in ultra-processed products.",
            ),
        ),
        allIngredients = listOf("Sugar", "High Fructose Corn Syrup", "Natural Flavor"),
        engineLabel = "Gemini staged LLM",
        confidence = 0.88f,
    )
}
