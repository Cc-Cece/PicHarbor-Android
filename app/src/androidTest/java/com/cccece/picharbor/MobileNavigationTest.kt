package com.cccece.picharbor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class MobileNavigationTest {
    @get:Rule val compose=createComposeRule()
    @Test fun fourNativeTabsExposeTheirActualRootContent() {
        val model=HarborViewModel(ApplicationProvider.getApplicationContext())
        compose.setContent { MobileLocale("zh-Hans") { HarborRoot(model,{},{},{},{},{}) } }
        listOf("照片","备份","活动","设置").forEach{compose.onAllNodesWithText(it).onFirst().assertExists()}
        compose.onNode(hasText("活动") and hasClickAction()).performClick()
        compose.onNodeWithText("全部来源").assertExists()
        compose.onNode(hasText("设置") and hasClickAction()).performClick()
        compose.onNodeWithText("应用内代理").assertExists()
        compose.onNodeWithText("活动记录").assertExists()
        compose.onNode(hasText("照片") and hasClickAction()).performClick()
        compose.onNodeWithText("全部可访问项目").assertExists()
        model.graph.scope.cancelForTest()
    }
}

private fun kotlinx.coroutines.CoroutineScope.cancelForTest() {
    coroutineContext[kotlinx.coroutines.Job]?.cancel()
}
