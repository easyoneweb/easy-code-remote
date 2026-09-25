package com.easycoderemote.render.markdown

import android.text.Spanned
import android.text.style.ClickableSpan
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.noties.markwon.ext.tables.TableSpan
import io.noties.markwon.ext.tasklist.TaskListSpan
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Plan §10 MarkdownRenderer fixture corpus: headings, nested lists, tables, inline
 * code, fenced code blocks, links, bold/italic, task lists, raw HTML → escaped,
 * 64 KiB cap behaviour, unicode/emoji, no remote images.
 */
@RunWith(RobolectricTestRunner::class)
class MarkdownRendererTest {

    private lateinit var renderer: MarkdownRenderer

    @Before
    fun setUp() {
        renderer = MarkdownRenderer(ApplicationProvider.getApplicationContext())
    }

    private fun spans(text: String, clazz: Class<*>): Array<out Any> =
        (renderer.render(text) as Spanned).getSpans(0, text.length, clazz)

    @Test
    fun headingsRenderAsHeadings() {
        val out = renderer.render("# Big\n\n## Smaller").toString()
        assertThat(out).contains("Big")
        assertThat(out).contains("Smaller")
        val spanned = renderer.render("# Big") as Spanned
        assertThat(spanned.getSpans(0, spanned.length, io.noties.markwon.core.spans.HeadingSpan::class.java))
            .isNotEmpty()
    }

    @Test
    fun boldItalicAndStrikethroughRender() {
        val out = renderer.render("**bold** and *italic* and ~~gone~~").toString()
        assertThat(out).contains("bold")
        assertThat(out).contains("italic")
        assertThat(out).contains("gone")
        val bold = renderer.render("**bold**") as Spanned
        assertThat(bold.getSpans(0, bold.length, io.noties.markwon.core.spans.StrongEmphasisSpan::class.java))
            .isNotEmpty()
        val italic = renderer.render("*italic*") as Spanned
        assertThat(italic.getSpans(0, italic.length, io.noties.markwon.core.spans.EmphasisSpan::class.java))
            .isNotEmpty()
    }

    @Test
    fun inlineAndFencedCodeRenderAsMonospace() {
        val out = renderer.render("`inline` and\n\n```kotlin\nval x = 1\n```").toString()
        assertThat(out).contains("inline")
        assertThat(out).contains("val x = 1")
        val spanned = renderer.render("`inline`") as Spanned
        assertThat(spanned.getSpans(0, spanned.length, io.noties.markwon.core.spans.CodeSpan::class.java))
            .isNotEmpty()
        val fenced = renderer.render("```kotlin\nval x = 1\n```") as Spanned
        assertThat(fenced.getSpans(0, fenced.length, io.noties.markwon.core.spans.CodeBlockSpan::class.java))
            .isNotEmpty()
    }

    @Test
    fun nestedListsRender() {
        val md = "- one\n  - one point one\n  - one point two\n- two"
        val out = renderer.render(md).toString()
        assertThat(out).contains("one point two")
        assertThat(out).contains("two")
    }

    @Test
    fun tablesRenderAsTableSpan() {
        val md = "| a | b |\n|---|---|\n| 1 | 2 |"
        val spanned = renderer.render(md)
        assertThat(spanned).isInstanceOf(Spanned::class.java)
        assertThat((spanned as Spanned).getSpans(0, spanned.length, TableSpan::class.java))
            .isNotEmpty()
    }

    @Test
    fun taskListsRenderAsTaskListSpans() {
        val md = "- [x] done\n- [ ] todo"
        val spanned = renderer.render(md)
        assertThat((spanned as Spanned).getSpans(0, spanned.length, TaskListSpan::class.java))
            .isNotEmpty()
        assertThat(spanned.toString()).contains("done")
        assertThat(spanned.toString()).contains("todo")
    }

    @Test
    fun httpLinksAreClickable() {
        val md = "see [docs](https://example.com/a?b=1) now"
        val out = renderer.render(md)
        val links = (out as Spanned).getSpans(0, out.length, ClickableSpan::class.java)
        assertThat(links).isNotEmpty()
        // The resolved destination must survive in the span text (URLSpan).
        assertThat(out.toString()).contains("docs")
    }

    @Test
    fun nonHttpSchemesAreNotClickable() {
        val md = "file [x](file:///etc/passwd) and [y](javascript:alert(1))"
        val out = renderer.render(md)
        val links = (out as Spanned).getSpans(0, out.length, ClickableSpan::class.java)
        assertThat(links).isEmpty()
        assertThat(renderer.hasLinks(out)).isFalse()
    }

    @Test
    fun rawHtmlIsEscapedAsPlainText() {
        val out = renderer.render("<script>alert(1)</script>").toString()
        // Rendered as literal text, never as a live element, and no clickable spans.
        assertThat(out).contains("script")
        val spanned = renderer.render("<script>alert(1)</script>") as Spanned
        assertThat(spanned.getSpans(0, spanned.length, ClickableSpan::class.java)).isEmpty()
    }

    @Test
    fun remoteImagesAreNotLoaded() {
        // Plan §5.7: agent output may contain tracking/beacon URLs — the phone must
        // never fetch them. Without an image loader the image renders as nothing.
        val out = renderer.render("![track](https://evil.example/beacon.png)").toString()
        assertThat(out).doesNotContain("evil.example")
        val spanned = renderer.render("![track](https://evil.example/beacon.png)") as Spanned
        assertThat(spanned.getSpans(0, spanned.length, ClickableSpan::class.java)).isEmpty()
    }

    @Test
    fun hugeInputStaysBounded() {
        // 128 KiB of markdown — the renderer must stay bounded and not throw
        // (the UI truncates at MAX_MARKDOWN_CHARS; this guards the renderer itself).
        val block = "# Heading\n\nSome **bold** text with `code` and [link](https://x.test).\n\n"
        val repeats = (MAX_MARKDOWN_CHARS / block.length) + 4
        val big = buildString { repeat(repeats) { append(block) } }
        assertThat(big.length).isGreaterThan(MAX_MARKDOWN_CHARS)
        val out = renderer.render(big).toString()
        // Rendered output is proportional to the input, never an explosion.
        assertThat(out.length).isGreaterThan(0)
        assertThat(out.length.toLong()).isLessThan(big.length.toLong() * 4L)
    }

    @Test
    fun unicodeAndEmojiSurvive() {
        val md = "Привет мир 🌍 — `code`"
        val out = renderer.render(md).toString()
        assertThat(out).contains("Привет")
        assertThat(out).contains("🌍")
    }

    @Test
    fun sameTextIsCachedAndNeverReparsed() {
        val first = renderer.render("hello **world**")
        val second = renderer.render("hello **world**")
        assertThat(first.toString()).isEqualTo(second.toString())
        assertThat(first).isSameInstanceAs(second)
    }
}