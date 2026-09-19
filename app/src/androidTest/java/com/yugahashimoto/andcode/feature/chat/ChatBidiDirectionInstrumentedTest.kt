package com.yugahashimoto.andcode.feature.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression tests for issue #341: mixed Arabic/English (RTL/LTR) chat text must resolve its
 * paragraph direction from the content, not from the app's layout direction, otherwise lines
 * render reversed ("start of line at the end").
 */
@RunWith(AndroidJUnit4::class)
class ChatBidiDirectionInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun paragraphDirectionOf(
        text: String,
        offset: Int,
    ): ResolvedTextDirection {
        val node = composeRule.onNodeWithText(text).fetchSemanticsNode()
        val fetchLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
        assertNotNull("No text layout result semantics on node for: $text", fetchLayout)
        val layouts = mutableListOf<TextLayoutResult>()
        fetchLayout!!.action!!.invoke(layouts)
        return layouts.single().multiParagraph.getParagraphDirection(offset)
    }

    @Test
    fun userBubbleMixedArabicEnglishResolvesRtlParagraphDirection() {
        val text = "مرحبا world مرحبا"
        composeRule.setContent {
            MessageBubble(ChatMessage(isUser = true, parts = listOf(ChatPart.Text("m1", text))))
        }
        composeRule.waitForIdle()
        assertEquals(ResolvedTextDirection.Rtl, paragraphDirectionOf(text, text.length / 2))
    }

    @Test
    fun assistantMarkdownArabicParagraphResolvesRtlParagraphDirection() {
        val text = "هذا مثال على نص عربي ممزوج مع English words في نفس السطر"
        composeRule.setContent {
            TimelineEntryRow(TimelineEntry.Body("b1", "m1", ChatPart.Text("p1", text)))
        }
        composeRule.waitForIdle()
        assertEquals(ResolvedTextDirection.Rtl, paragraphDirectionOf(text, text.length / 2))
    }

    @Test
    fun userBubbleLatinTextKeepsLtrParagraphDirection() {
        val text = "Hello world مرحبا"
        composeRule.setContent {
            MessageBubble(ChatMessage(isUser = true, parts = listOf(ChatPart.Text("m2", text))))
        }
        composeRule.waitForIdle()
        assertEquals(ResolvedTextDirection.Ltr, paragraphDirectionOf(text, text.length / 3))
    }

    @Test
    fun userBubbleResolvesParagraphDirectionPerLine() {
        val text = "Hello there\nمرحبا من أنا"
        composeRule.setContent {
            MessageBubble(ChatMessage(isUser = true, parts = listOf(ChatPart.Text("m3", text))))
        }
        composeRule.waitForIdle()
        assertEquals(ResolvedTextDirection.Ltr, paragraphDirectionOf(text, 2))
        assertEquals(ResolvedTextDirection.Rtl, paragraphDirectionOf(text, text.length - 2))
    }

    @Test
    fun errorPartCardArabicMessageResolvesRtlParagraphDirection() {
        val text = "خطأ داخلي: فشل تشغيل الأمر بسبب مشكلة في الشبكة"
        composeRule.setContent {
            TimelineEntryRow(TimelineEntry.Error("e1", ChatPart.Error("e1", text)))
        }
        composeRule.waitForIdle()
        assertEquals(ResolvedTextDirection.Rtl, paragraphDirectionOf(text, text.length / 2))
    }
}
