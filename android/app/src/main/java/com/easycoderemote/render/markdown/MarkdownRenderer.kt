package com.easycoderemote.render.markdown

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App-wide Markdown renderer (plan §5.7): one [Markwon] instance shared by every
 * transcript bubble, so plugin/theme setup happens exactly once.
 *
 * Security posture (plan §5.7):
 * - No image plugin or [io.noties.markwon.image.AsyncDrawableLoader] is configured:
 *   `![..](..)` parses but renders as nothing, so tracking/beacon URLs in agent
 *   output are never fetched by the phone.
 * - Raw HTML is escaped by CommonMark (Markwon default).
 * - [SafeLinkPlugin] whitelists `https://`/`http://`; every other scheme/link is
 *   inert and never opens an intent.
 *
 * A small bounded cache keeps the final [Spannable] for completed messages so the
 * same text is never re-parsed (plan §5.7: "completed messages keep their final
 * Spannable (never re-parsed)").
 */
class MarkdownRenderer private constructor(context: Context) {

    companion object {
        @Volatile
        private var instance: MarkdownRenderer? = null

        /** App-wide singleton (plan §5.7: "one Markwon instance reused app-wide"). */
        fun get(context: Context): MarkdownRenderer =
            instance ?: synchronized(this) {
                instance ?: MarkdownRenderer(context.applicationContext).also { instance = it }
            }
    }

    private val markwon: Markwon = Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(TaskListPlugin.create(context))
        .usePlugin(SafeLinkPlugin)
        .build()

    private val cache: LinkedHashMap<String, CharSequence> =
        object : LinkedHashMap<String, CharSequence>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CharSequence>?): Boolean =
                size > 128
        }

    /** Parses + renders synchronously (small snippets, tests). */
    fun render(text: String): CharSequence {
        synchronized(cache) {
            cache[text]?.let { return it }
        }
        val result = markwon.toMarkdown(text)
        sanitizeLinks(result)
        synchronized(cache) { cache[text] = result }
        return result
    }

    /**
     * Markwon creates clickable spans for every link; the [SafeLinkPlugin] resolver
     * only gates what happens on tap. Stripping non-http(s) spans at render time
     * makes those links fully inert (not even focusable/clickable).
     */
    private fun sanitizeLinks(result: CharSequence) {
        if (result !is Spannable) return
        for (span in result.getSpans(0, result.length, URLSpan::class.java)) {
            if (!isSafeLink(span.url)) result.removeSpan(span)
        }
    }

    private fun isSafeLink(url: String): Boolean =
        url.startsWith("https://") || url.startsWith("http://")

    /** Parses + renders off the main thread (plan §5.7: never parse on main). */
    suspend fun renderAsync(text: String): CharSequence = withContext(Dispatchers.Default) {
        render(text)
    }

    /** True when the rendered text has clickable spans (links) that need a movement method. */
    fun hasLinks(spanned: CharSequence): Boolean =
        spanned is Spanned &&
            spanned.getSpans(0, spanned.length, ClickableSpan::class.java).isNotEmpty()

    /** Only http(s) links ever resolve to an ACTION_VIEW; everything else is inert. */
    private object SafeLinkPlugin : AbstractMarkwonPlugin() {
        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
            builder.linkResolver { view, link ->
                if (link.startsWith("https://") || link.startsWith("http://")) {
                    runCatching {
                        view.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                    }
                }
                // Non-http(s): deliberately ignored — no ACTION_VIEW, no file/intent tricks.
            }
        }
    }
}
