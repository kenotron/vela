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
                if (!response.isSuccessful) return@runCatching "HTTP \${response.code} error for $url"

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
                val trimmed = if (text.length > 1200) text.take(1200) + "…[truncated]" else text
                "Fetched $url:\n$trimmed"
            }.getOrElse { e -> "Error fetching $url: \${e.message?.take(120)}" }
        }
    }

    class SearchWebTool(private val client: OkHttpClient) : Tool {
        override val name        = "search_web"
        override val displayName = "Web Search"
        override val icon        = "🔍"
        override val description = "Searches the web and returns relevant snippets with URLs"
        override val parameters  = listOf(
            ToolParameter("query", "string", "the search query"),
        )

        override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
            val query = args["query"] as? String
                ?: return@withContext "Error: 'query' parameter is required"

            runCatching {
                val encoded  = URLEncoder.encode(query, "UTF-8")
                val url      = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
                val request  = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                val body     = response.body?.string() ?: return@runCatching "No response from search"
                val json     = JSONObject(body)
                val sb       = StringBuilder("Search results for \"$query\":\n")

                val abstract    = json.optString("AbstractText").take(300)
                val abstractUrl = json.optString("AbstractURL")
                if (abstract.isNotBlank()) {
                    sb.append("• $abstract")
                    if (abstractUrl.isNotBlank()) sb.append("\n  URL: $abstractUrl")
                    sb.append("\n")
                }

                val topics = json.optJSONArray("RelatedTopics")
                if (topics != null) {
                    (0 until topics.length())
                        .mapNotNull { i -> topics.optJSONObject(i) }
                        .filter { it.optString("Text").isNotBlank() }
                        .take(3)
                        .forEach { obj ->
                            val text     = obj.optString("Text").take(200)
                            val firstUrl = obj.optString("FirstURL")
                            sb.append("• $text")
                            if (firstUrl.isNotBlank()) sb.append("\n  URL: $firstUrl")
                            sb.append("\n")
                        }
                }

                val result = sb.toString().trimEnd()
                if (result == "Search results for \"$query\":") "No results found for: $query"
                else result
            }.getOrElse { e -> "Error searching for '$query': \${e.message?.take(120)}" }
        }
    }
    