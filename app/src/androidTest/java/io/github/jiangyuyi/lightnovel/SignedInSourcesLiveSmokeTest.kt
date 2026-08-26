package io.github.jiangyuyi.lightnovel

import android.util.Log
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToLog
import org.junit.Rule
import org.junit.Test

class SignedInSourcesLiveSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun searchDetailsAndReaderWorkForBothSignedInSources() {
        compose.onNodeWithText("搜索").performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("魔法")
        compose.onAllNodes(hasSetTextAction())[0].performImeAction()

        val kingdomResult = resultCard("轻之国度")
        val shelfResult = resultCard("轻书架")
        waitFor(kingdomResult)
        compose.onRoot(useUnmergedTree = true).printToLog("LIVE-SMOKE-SEARCH")
        waitFor(shelfResult)

        verifyFirstResult("轻书架", shelfResult)
        verifyFirstResult("轻之国度", kingdomResult)
    }

    private fun verifyFirstResult(sourceName: String, resultMatcher: SemanticsMatcher) {
        compose.onAllNodes(resultMatcher, useUnmergedTree = true)[0].performClick()
        waitFor(hasText("开始阅读"))
        check(compose.onAllNodesWithText("重试").fetchSemanticsNodes().isEmpty()) {
            "$sourceName detail failed to load"
        }
        compose.onRoot(useUnmergedTree = true).printToLog("LIVE-SMOKE-DETAIL-$sourceName")

        compose.onNodeWithText("开始阅读").performClick()
        waitFor(hasText("目录"))
        compose.waitUntil(45_000) {
            compose.onAllNodesWithText("阅读").fetchSemanticsNodes().isEmpty()
        }
        check(compose.onAllNodesWithText("重试").fetchSemanticsNodes().isEmpty()) {
            "$sourceName chapter failed to load"
        }
        compose.onRoot(useUnmergedTree = true).printToLog("LIVE-SMOKE-READER-$sourceName")
        Log.i("NOVAL-LIVE-SMOKE", "$sourceName detail, directory and chapter loaded")

        compose.onNodeWithText("目录").performClick()
        waitFor(hasText("开始阅读"))
        compose.onNodeWithText("返回").performClick()
        waitFor(hasSetTextAction())
    }

    private fun resultCard(sourceName: String): SemanticsMatcher =
        hasClickAction() and hasAnyDescendant(hasText(sourceName, substring = true))

    private fun waitFor(matcher: SemanticsMatcher) {
        compose.waitUntil(45_000) {
            compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
