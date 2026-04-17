    package com.vela.app.ai.tools

    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext
    import okhttp3.OkHttpClient
    import okhttp3.Request
    import org.json.JSONObject
    import java.net.URLEncoder

    class FetchUrlTool(private val client: OkHttpClient) : Tool {
        override val name        = "fetch_url"
        override val displayName = "Fetch URL"
        override val icon        = "🌐"
        override val description = "Fetches the text content of a URL (HTTP GET)"
        override val parameters  = listOf(
            ToolParameter("url", "string", "the full URL to fetch (must start with http/https)"),
        )

        override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
            val url = args["url"] as? String ?: return@withContext "Error: 'url' parameter is required"
            if (!url.startsWith("http://") && !url.startsWith("https://"))
                return@withContext "Error: URL must start with http:// or https://"

            runCatching {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return@runCatching "HTTP ${response.code} error for $url"

                val contentType = response.header("Content-Type", "").orEmpty()
                val text = when {
                    "html" in contentType -> body
                        .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("<style[^>]*>[\\s\\S]*?</style>",  RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("&[a-z]+;"), " ")
                        .replace(Regex("\\s+"), " ").trim()
                    else -> body.replace(Regex("\\s+"), " ").trim()
                }
                val trimmed = if (text.length > 8_000) text.take(8_000) + "…[truncated]" else text
                "Fetched $url:\n$trimmed"
            }.getOrElse { e -> "Error fetching $url: ${e.message?.take(120)}" }
        }
    }

    /**
     * Web search tool using DuckDuckGo's HTML endpoint.
     *
     * Uses https://html.duckduckgo.com/html/ which returns real web results (titles,
     * URLs, snippets) — much better than the Instant Answer JSON API which only
     * returns Wikipedia summaries.
     *
     * NOTE: When using Anthropic as the provider, Claude has native web search
     * capability built into the API (web_search_20250305 tool). That is a better
     * option and doesn't require this tool at all — it's handled server-side by
     * Anthropic. This tool is useful when the provider doesn't offer native search.
     */
    class SearchWebTool(private val client: OkHttpClient) : Tool {
        override val name        = "search_web"
        override val displayName = "Web Search"
        override val icon        = "🔍"
        override val description = "Searches the web and returns results with titles, URLs, and snippets"
        override val parameters  = listOf(
            ToolParameter("query", "string", "the search query"),
        )

        override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
            val query = args["query"] as? String
                ?: return@withContext "Error: 'query' parameter is required"

            searchHtml(query).takeIf { !it.startsWith("Error") && it != "No results found for: $query" }
                ?: searchInstantAnswer(query)
        }

        // ── DuckDuckGo HTML search ─────────────────────────────────────────────────
        // Scrapes https://html.duckduckgo.com/html/ for real web results.

        private fun searchHtml(query: String): String = runCatching {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("https://html.duckduckgo.com/html/?q=$encoded")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                .build()
            val html = client.newCall(request).execute().body?.string() ?: return@runCatching "No response"

            // Extract result blocks: title + url + snippet
            val titleRx   = Regex("""class="result__a"[^>]*>([^<]{3,100})<""")
            val urlRx     = Regex("""class="result__url"[^>]*>\s*([^\s<]{5,200})\s*<""")
            val snippetRx = Regex("""class="result__snippet"[^>]*>([^<]{10,400})<""")

            val titles   = titleRx.findAll(html).map { it.groupValues[1].trim() }.toList()
            val urls     = urlRx.findAll(html).map { it.groupValues[1].trim() }.toList()
            val snippets = snippetRx.findAll(html).map { it.groupValues[1].trim() }.toList()

            if (titles.isEmpty()) return@runCatching "No results found for: $query"

            buildString {
                appendLine("Search results for \"$query\":")
                titles.take(6).forEachIndexed { i, title ->
                    appendLine("• $title")
                    urls.getOrNull(i)?.let  { appendLine("  $it") }
                    snippets.getOrNull(i)?.let { appendLine("  $it") }
                    appendLine()
                }
            }.trimEnd()
        }.getOrElse { e -> "Error: ${e.message?.take(100)}" }

        // ── DuckDuckGo Instant Answer (fallback) ───────────────────────────────────
        // Only returns Wikipedia-style summaries. Falls back here if HTML parse fails.

        private fun searchInstantAnswer(query: String): String = runCatching {
            val encoded  = URLEncoder.encode(query, "UTF-8")
            val url      = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val body     = client.newCall(Request.Builder().url(url).build()).execute().body?.string()
                ?: return@runCatching "No results found for: $query"
            val json     = JSONObject(body)
            val sb       = StringBuilder("Search results for \"$query\":\n")

            json.optString("AbstractText").take(300).takeIf { it.isNotBlank() }?.let {
                sb.append("• $it")
                json.optString("AbstractURL").takeIf { u -> u.isNotBlank() }?.let { u -> sb.append("\n  $u") }
                sb.append("\n")
            }

            json.optJSONArray("RelatedTopics")?.let { topics ->
                (0 until topics.length())
                    .mapNotNull { topics.optJSONObject(it) }
                    .filter { it.optString("Text").isNotBlank() }
                    .take(3)
                    .forEach { obj ->
                        sb.append("• ${obj.optString("Text").take(200)}")
                        obj.optString("FirstURL").takeIf { it.isNotBlank() }?.let { sb.append("\n  $it") }
                        sb.append("\n")
                    }
            }

            sb.toString().trimEnd().takeIf { it != "Search results for \"$query\":" }
                ?: "No results found for: $query"
        }.getOrElse { e -> "Error searching for '$query': ${e.message?.take(120)}" }
    }
