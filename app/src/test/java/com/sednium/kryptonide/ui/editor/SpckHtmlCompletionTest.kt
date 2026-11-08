package com.sednium.kryptonide.ui.editor

import android.os.Handler
import android.os.Looper
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
class SpckHtmlCompletionTest {

    private fun createPublisher(): CompletionPublisher {
        return CompletionPublisher(Handler(Looper.getMainLooper()), Runnable {}, 0).apply {
            setUpdateThreshold(1)
        }
    }

    private fun flushItems(publisher: CompletionPublisher): List<String> {
        publisher.updateList(true)
        ShadowLooper.idleMainLooper()
        // Combine items and candidates via reflection if any remain in candidates
        val list = mutableListOf<String>()
        list.addAll(publisher.items.map { it.label.toString() })
        val candidatesField = CompletionPublisher::class.java.getDeclaredField("candidates").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val candidates = candidatesField.get(publisher) as? List<io.github.rosemoe.sora.lang.completion.CompletionItem>
        candidates?.let { list.addAll(it.map { item -> item.label.toString() }) }
        return list.distinct()
    }

    @Test
    fun testPlainTextContentSuppressesHtmlTagPopups() {
        val text = "<p>Hi, this is a demo video</p>"
        val content = Content(text)
        val ref = ContentReference(content)
        val pos = CharPosition().apply {
            line = 0
            column = "<p>Hi, this is a demo video".length
        }
        val publisher = createPublisher()
        val handled = SpckCompletionEngine.populateCompletions("html", ref, pos, "video", publisher)

        val suggestedLabels = flushItems(publisher)
        assertFalse(handled)
        assertFalse(suggestedLabels.any { it.contains("<video>") })
        assertFalse(suggestedLabels.any { it.contains("<title>") })
        assertFalse(suggestedLabels.any { it.contains("<a>") })
    }

    @Test
    fun testOpenTagTriggersHtmlTagCompletions() {
        val text = "<p>Hi</p>\n<v"
        val content = Content(text)
        val ref = ContentReference(content)
        val pos = CharPosition().apply {
            line = 1
            column = 2 // right after "<v"
        }
        val publisher = createPublisher()
        val handled = SpckCompletionEngine.populateCompletions("html", ref, pos, "v", publisher)

        val suggestedLabels = flushItems(publisher)
        assertTrue(handled)
        assertTrue(suggestedLabels.any { it == "<video>" })
    }

    @Test
    fun testCloseTagSuggestsParentTag() {
        val text = "<div><p>Hello</"
        val content = Content(text)
        val ref = ContentReference(content)
        val pos = CharPosition().apply {
            line = 0
            column = text.length // right after "</"
        }
        val publisher = createPublisher()
        val handled = SpckCompletionEngine.populateCompletions("html", ref, pos, "", publisher)

        val suggestedLabels = flushItems(publisher)
        assertTrue(handled)
        assertTrue(suggestedLabels.any { it == "</p>" })
    }
}
