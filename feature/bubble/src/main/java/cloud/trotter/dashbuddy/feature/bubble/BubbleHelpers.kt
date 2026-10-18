package cloud.trotter.dashbuddy.feature.bubble

import android.text.Html
fun getPreviewText(html: String): String {
    if (html.isBlank()) return ""

    val spacedHtml = html
        .replace("<br>", " // ")
        .replace("<br/>", " // ")
        .replace("</p>", " // ")
        .replace("</div>", " // ")

    val spanned = Html.fromHtml(spacedHtml, Html.FROM_HTML_MODE_COMPACT)
    return spanned.toString().trim()
}
