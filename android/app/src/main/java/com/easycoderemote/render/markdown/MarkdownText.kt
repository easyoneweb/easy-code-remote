package com.easycoderemote.render.markdown

import android.text.method.LinkMovementMethod
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import kotlinx.coroutines.delay

/** Transcript parts above this many chars render truncated with a "Show full text" toggle. */
const val MAX_MARKDOWN_CHARS = 64 * 1024

private const val STREAM_DEBOUNCE_MS = 200L

/**
 * Markwon-rendered markdown in a plain [TextView] inside Compose (plan §5.7):
 * native text rendering, no WebView. Assistant text parts only.
 *
 * - Parsing happens on [kotlinx.coroutines.Dispatchers.Default] with a ~200 ms
 *   debounce while [isStreaming]; completed messages parse once per distinct text
 *   (the renderer caches the final Spannable).
 * - While a re-render is in flight the previous output stays visible, so live
 *   streaming never flickers back to plain text.
 * - Messages above [MAX_MARKDOWN_CHARS] show a truncated preview + "Show full text".
 */
@Composable
fun MarkdownText(
    rawText: String,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    linkColor: Color = MaterialTheme.colorScheme.primary,
) {
    val context = LocalContext.current
    val renderer = remember { MarkdownRenderer(context) }
    var expanded by remember { mutableStateOf(false) }

    val capped = rawText.length > MAX_MARKDOWN_CHARS
    val text = if (capped && !expanded) rawText.take(MAX_MARKDOWN_CHARS) else rawText

    // Keep the previous parse visible while the next one is computed. The initial
    // frame for a freshly opened message falls back to the raw text (parsed within
    // milliseconds) instead of blank.
    var rendered by remember { mutableStateOf<CharSequence?>(null) }
    LaunchedEffect(text, isStreaming) {
        if (isStreaming) delay(STREAM_DEBOUNCE_MS)
        rendered = renderer.renderAsync(text)
    }

    Column(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxWidth(),
            factory = { ctx ->
                TextView(ctx).apply {
                    textSize = 14.sp.value
                }
            },
            update = { tv ->
                tv.setTextColor(textColor.toArgb())
                tv.setLinkTextColor(linkColor.toArgb())
                val cs = rendered
                if (cs != null) {
                    tv.text = cs
                    tv.movementMethod = if (renderer.hasLinks(cs)) {
                        // Wrap so table cells stay tappable inside a scrolling list.
                        TableAwareMovementMethod.wrap(LinkMovementMethod.getInstance())
                    } else {
                        null
                    }
                } else {
                    tv.text = text
                    tv.movementMethod = null
                }
            },
        )
        if (capped && !expanded) {
            TextButton(onClick = { expanded = true }) { Text("Show full text") }
        }
    }
}
