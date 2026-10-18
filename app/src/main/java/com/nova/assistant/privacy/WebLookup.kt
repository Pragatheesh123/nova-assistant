package com.nova.assistant.privacy

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Searches the web (DuckDuckGo) and reads the top pages so Nova can answer current questions. */
object WebLookup {
    data class Result(val title: String, val url: String, val snippet: String)
    data class Digest(val text: String, val results: List<Result>)

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun online(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun http(url: String, post: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        if (post != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(post.toByteArray()) }
        }
        return c.inputStream.bufferedReader().use { it.readText().take(600_000) }
    }

    private fun strip(html: String) =
        Html.fromHtml(html.replace(Regex("<[^>]+>"), ""), Html.FROM_HTML_MODE_LEGACY).toString().trim()

    fun parse(raw: String, n: Int = 5): List<Result> {
        val out = mutableListOf<Result>()
        for (b in raw.split(Regex("<div class=\"result[ \"]")).drop(1)) {
            if (b.take(200).contains("result--ad")) continue
            val a = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b) ?: continue
            var href = a.groupValues[1].replace("&amp;", "&")
            if (href.contains("duckduckgo.com/y.js")) continue
            Regex("[?&]uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            val sn = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(b)?.groupValues?.get(1).orEmpty()
            out += Result(strip(a.groupValues[2]), href, strip(sn))
            if (out.size >= n) break
        }
        return out
    }

    private fun pageText(html: String, limit: Int = 1500): String {
        val body = html
            .replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|form|aside)\\b.*?</\\1>"), " ")
            .replace(Regex("(?s)<[^>]+>"), " ")
        return Html.fromHtml(body, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim().take(limit)
    }

    suspend fun lookup(query: String, pages: Int = 1): Digest = withContext(Dispatchers.IO) {
        val raw = http("https://html.duckduckgo.com/html/", "q=" + URLEncoder.encode(query, "UTF-8"))
        val results = parse(raw)
        val sb = StringBuilder("Search results:\n")
        results.forEachIndexed { i, r -> sb.append("${i + 1}. ${r.title} (${URL(r.url).host}) - ${r.snippet}\n") }
        var read = 0
        for (r in results) {
            if (read >= pages || listOf("youtube.com", "instagram.com", "facebook.com", "x.com").any { r.url.contains(it) }) continue
            try {
                sb.append("\nFrom ${URL(r.url).host}: ").append(pageText(http(r.url))).append('\n')
                read++
            } catch (e: Exception) { }
        }
        Digest(sb.toString(), results)
    }
}
