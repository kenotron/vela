package com.vela.app.ai.tools

import android.util.Log
import com.vela.app.github.GitHubIdentityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

private const val TAG = "GitHubTool"
private const val GH_API = "https://api.github.com"
private val JSON_MT = "application/json".toMediaType()

/**
 * Comprehensive GitHub tool — a gh-CLI substitute using the REST + GraphQL APIs.
 *
 * Supports repos, issues, PRs, releases, projects, code browsing, and repo cloning.
 * Uses stored GitHub identities (Settings → GitHub) for authentication.
 */
class GitHubTool(
    private val identityManager: GitHubIdentityManager,
    private val client: OkHttpClient,
) : Tool {

    override val name        = "github"
    override val displayName = "GitHub"
    override val icon        = "🐙"
    override val description = """
        Full GitHub integration — repos, issues, PRs, releases, projects, code.

        Actions:
          Repos:    list_repos, get_repo
          Issues:   list_issues, get_issue, create_issue, update_issue, comment
          PRs:      list_prs, get_pr, create_pr, merge_pr
          Code:     get_file, list_tree, search, clone_repo
          Releases: list_releases, create_release
          Projects: list_projects, get_project_items

        Use the `identity` param to pick a specific GitHub account label; omit to use the default.
    """.trimIndent()

    override val parameters = listOf(
        ToolParameter("action",   "string",  "Action to perform (see description)"),
        ToolParameter("repo",     "string",  "owner/repo (required for most actions)", required = false),
        ToolParameter("number",   "integer", "Issue or PR number",                     required = false),
        ToolParameter("title",    "string",  "Title for create actions",               required = false),
        ToolParameter("body",     "string",  "Body / description text",                required = false),
        ToolParameter("state",    "string",  "Filter: open | closed | all",            required = false),
        ToolParameter("labels",   "string",  "Comma-separated label names",            required = false),
        ToolParameter("assignees","string",  "Comma-separated GitHub logins",          required = false),
        ToolParameter("branch",   "string",  "Branch name (head for PR, ref for clone)",required = false),
        ToolParameter("base",     "string",  "Base branch for PR creation",            required = false),
        ToolParameter("path",     "string",  "File/dir path in repo or local path",    required = false),
        ToolParameter("query",    "string",  "Search query",                           required = false),
        ToolParameter("identity", "string",  "GitHub identity label (default account if omitted)", required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val action   = args["action"]   as? String ?: return@withContext "Error: action is required"
        val repo     = args["repo"]     as? String
        val number   = (args["number"] as? Number)?.toInt()
        val title    = args["title"]    as? String
        val body     = args["body"]     as? String
        val state    = args["state"]    as? String ?: "open"
        val labels   = args["labels"]   as? String
        val assignees= args["assignees"]as? String
        val branch   = args["branch"]   as? String ?: "main"
        val base     = args["base"]     as? String ?: "main"
        val path     = args["path"]     as? String
        val query    = args["query"]    as? String
        val identity = args["identity"] as? String

        val token = identityManager.tokenFor(identity)
            ?: return@withContext "Error: No GitHub identity configured. Add one in Settings → GitHub."

        try {
            when (action) {
                // ── Repos ──────────────────────────────────────────────────────────────
                "list_repos"   -> listRepos(token, query)
                "get_repo"     -> getRepo(token, repo ?: return@withContext "Error: repo required")

                // ── Issues ─────────────────────────────────────────────────────────────
                "list_issues"  -> listIssues(token, repo ?: return@withContext "Error: repo required", state, labels)
                "get_issue"    -> getIssue(token, repo ?: return@withContext "Error: repo required", number ?: return@withContext "Error: number required")
                "create_issue" -> createIssue(token, repo ?: return@withContext "Error: repo required", title ?: return@withContext "Error: title required", body, labels, assignees)
                "update_issue" -> updateIssue(token, repo ?: return@withContext "Error: repo required", number ?: return@withContext "Error: number required", title, body, state, labels)
                "comment"      -> addComment(token, repo ?: return@withContext "Error: repo required", number ?: return@withContext "Error: number required", body ?: return@withContext "Error: body required")

                // ── Pull Requests ──────────────────────────────────────────────────────
                "list_prs"     -> listPRs(token, repo ?: return@withContext "Error: repo required", state)
                "get_pr"       -> getPR(token, repo ?: return@withContext "Error: repo required", number ?: return@withContext "Error: number required")
                "create_pr"    -> createPR(token, repo ?: return@withContext "Error: repo required", title ?: return@withContext "Error: title required", body, branch, base)
                "merge_pr"     -> mergePR(token, repo ?: return@withContext "Error: repo required", number ?: return@withContext "Error: number required")

                // ── Code ───────────────────────────────────────────────────────────────
                "get_file"     -> getFile(token, repo ?: return@withContext "Error: repo required", path ?: return@withContext "Error: path required", branch)
                "list_tree"    -> listTree(token, repo ?: return@withContext "Error: repo required", path, branch)
                "search"       -> search(token, query ?: return@withContext "Error: query required", repo)
                "clone_repo"   -> cloneRepo(token, repo ?: return@withContext "Error: repo required", path, branch)

                // ── Releases ───────────────────────────────────────────────────────────
                "list_releases" -> listReleases(token, repo ?: return@withContext "Error: repo required")
                "create_release" -> createRelease(token, repo ?: return@withContext "Error: repo required", title ?: return@withContext "Error: title required", body, branch)

                // ── Projects (GraphQL) ─────────────────────────────────────────────────
                "list_projects"      -> listProjects(token, repo)
                "get_project_items"  -> getProjectItems(token, query ?: return@withContext "Error: query (project number/id) required")

                else -> "Unknown action: $action. See tool description for available actions."
            }
        } catch (e: Exception) {
            Log.e(TAG, "GitHub API error: action=$action", e)
            "Error: ${e.message}"
        }
    }

    // ── Repos ─────────────────────────────────────────────────────────────────

    private fun listRepos(token: String, query: String?): String {
        val url = if (query != null) "$GH_API/search/repositories?q=${query.encode()}&per_page=20"
                  else "$GH_API/user/repos?per_page=50&sort=pushed&affiliation=owner,collaborator"
        val arr = if (query != null) get(token, url).optJSONArray("items") ?: JSONArray()
                  else get(token, url) as? JSONArray ?: parseArray(getJson(token, url))
        return buildString {
            appendLine("Repositories (${arr.length()}):")
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val vis = if (r.optBoolean("private")) "private" else "public"
                appendLine("  ${r.getString("full_name")}  [$vis]  ★${r.optInt("stargazers_count")}  ${r.optString("description","").take(60)}")
            }
        }.trim()
    }

    private fun getRepo(token: String, repo: String): String {
        val r = get(token, "$GH_API/repos/$repo")
        return """
${r.getString("full_name")}
${r.optString("description","")}
Stars: ${r.optInt("stargazers_count")} | Forks: ${r.optInt("forks_count")} | Lang: ${r.optString("language","-")}
Default branch: ${r.optString("default_branch","main")}
URL: ${r.getString("html_url")}
Open issues: ${r.optInt("open_issues_count")}
        """.trimIndent()
    }

    // ── Issues ────────────────────────────────────────────────────────────────

    private fun listIssues(token: String, repo: String, state: String, labels: String?): String {
        var url = "$GH_API/repos/$repo/issues?state=$state&per_page=30&pulls=false"
        if (!labels.isNullOrBlank()) url += "&labels=${labels.encode()}"
        val arr = parseArray(getJson(token, url))
        return buildString {
            appendLine("Issues for $repo ($state) — ${arr.length()} shown:")
            for (i in 0 until arr.length()) {
                val iss = arr.getJSONObject(i)
                if (iss.has("pull_request")) continue  // skip PRs in issues list
                val num = iss.getInt("number")
                val ttl = iss.getString("title")
                val assignee = iss.optJSONObject("assignee")?.optString("login") ?: "-"
                appendLine("  #$num  $ttl  [@$assignee]")
            }
        }.trim()
    }

    private fun getIssue(token: String, repo: String, number: Int): String {
        val iss = get(token, "$GH_API/repos/$repo/issues/$number")
        val comments = parseArray(getJson(token, "$GH_API/repos/$repo/issues/$number/comments"))
        return buildString {
            appendLine("#${iss.getInt("number")}: ${iss.getString("title")}  [${iss.getString("state")}]")
            appendLine("By: ${iss.getJSONObject("user").getString("login")}  Created: ${iss.getString("created_at").take(10)}")
            val labelNames = (0 until iss.getJSONArray("labels").length()).map {
                iss.getJSONArray("labels").getJSONObject(it).getString("name")
            }
            if (labelNames.isNotEmpty()) appendLine("Labels: ${labelNames.joinToString()}")
            appendLine("\n${iss.optString("body","(no body)").take(1000)}")
            if (comments.length() > 0) {
                appendLine("\n── Comments (${comments.length()}) ──")
                for (i in 0 until minOf(comments.length(), 5)) {
                    val c = comments.getJSONObject(i)
                    appendLine("[@${c.getJSONObject("user").getString("login")}]: ${c.optString("body","").take(300)}")
                }
                if (comments.length() > 5) appendLine("  ... ${comments.length()-5} more")
            }
            appendLine("\nURL: ${iss.getString("html_url")}")
        }.trim()
    }

    private fun createIssue(token: String, repo: String, title: String, body: String?, labels: String?, assignees: String?): String {
        val payload = JSONObject().put("title", title)
        if (!body.isNullOrBlank()) payload.put("body", body)
        if (!labels.isNullOrBlank()) payload.put("labels", JSONArray(labels.split(",").map { it.trim() }))
        if (!assignees.isNullOrBlank()) payload.put("assignees", JSONArray(assignees.split(",").map { it.trim() }))
        val res = post(token, "$GH_API/repos/$repo/issues", payload)
        return "Created issue #${res.getInt("number")}: ${res.getString("title")}\n${res.getString("html_url")}"
    }

    private fun updateIssue(token: String, repo: String, number: Int, title: String?, body: String?, state: String, labels: String?): String {
        val payload = JSONObject()
        if (!title.isNullOrBlank()) payload.put("title", title)
        if (!body.isNullOrBlank())  payload.put("body", body)
        if (state == "closed" || state == "open") payload.put("state", state)
        if (!labels.isNullOrBlank()) payload.put("labels", JSONArray(labels.split(",").map { it.trim() }))
        val res = patch(token, "$GH_API/repos/$repo/issues/$number", payload)
        return "Updated #${res.getInt("number")}: ${res.getString("title")}  [${res.getString("state")}]"
    }

    private fun addComment(token: String, repo: String, number: Int, body: String): String {
        val res = post(token, "$GH_API/repos/$repo/issues/$number/comments", JSONObject().put("body", body))
        return "Comment posted: ${res.getString("html_url")}"
    }

    // ── Pull Requests ─────────────────────────────────────────────────────────

    private fun listPRs(token: String, repo: String, state: String): String {
        val arr = parseArray(getJson(token, "$GH_API/repos/$repo/pulls?state=$state&per_page=30"))
        return buildString {
            appendLine("Pull Requests for $repo ($state):")
            for (i in 0 until arr.length()) {
                val pr = arr.getJSONObject(i)
                val draft = if (pr.optBoolean("draft")) " [draft]" else ""
                appendLine("  #${pr.getInt("number")}$draft  ${pr.getString("title")}  by @${pr.getJSONObject("user").getString("login")}")
            }
        }.trim()
    }

    private fun getPR(token: String, repo: String, number: Int): String {
        val pr = get(token, "$GH_API/repos/$repo/pulls/$number")
        val reviews = parseArray(getJson(token, "$GH_API/repos/$repo/pulls/$number/reviews"))
        return buildString {
            appendLine("PR #${pr.getInt("number")}: ${pr.getString("title")}  [${pr.getString("state")}]")
            appendLine("By @${pr.getJSONObject("user").getString("login")} | ${pr.optString("head",pr.optJSONObject("head")?.optString("label","?") ?: "?")} → ${pr.optJSONObject("base")?.optString("label","?")}")
            appendLine("Mergeable: ${pr.opt("mergeable")}  Draft: ${pr.optBoolean("draft")}")
            appendLine("+${pr.optInt("additions")} -${pr.optInt("deletions")} across ${pr.optInt("changed_files")} files")
            appendLine("\n${pr.optString("body","(no body)").take(800)}")
            if (reviews.length() > 0) {
                appendLine("\nReviews:")
                for (i in 0 until reviews.length()) {
                    val r = reviews.getJSONObject(i)
                    appendLine("  @${r.getJSONObject("user").getString("login")}: ${r.getString("state")}")
                }
            }
            appendLine("\n${pr.getString("html_url")}")
        }.trim()
    }

    private fun createPR(token: String, repo: String, title: String, body: String?, head: String, base: String): String {
        val payload = JSONObject().put("title", title).put("head", head).put("base", base)
        if (!body.isNullOrBlank()) payload.put("body", body)
        val res = post(token, "$GH_API/repos/$repo/pulls", payload)
        return "Created PR #${res.getInt("number")}: ${res.getString("title")}\n${res.getString("html_url")}"
    }

    private fun mergePR(token: String, repo: String, number: Int): String {
        val res = put(token, "$GH_API/repos/$repo/pulls/$number/merge", JSONObject().put("merge_method","squash"))
        return if (res.optBoolean("merged")) "Merged PR #$number: ${res.optString("message")}"
               else "Merge failed: ${res.optString("message")}"
    }

    // ── Code ──────────────────────────────────────────────────────────────────

    private fun getFile(token: String, repo: String, path: String, ref: String): String {
        val res = get(token, "$GH_API/repos/$repo/contents/${path.trimStart('/')}?ref=$ref")
        val encoding = res.optString("encoding","")
        if (encoding == "base64") {
            val content = res.getString("content").replace("\n","")
            val bytes = android.util.Base64.decode(content, android.util.Base64.DEFAULT)
            val text = bytes.toString(Charsets.UTF_8)
            return if (text.length > 8000) "${text.take(8000)}\n\n[...truncated at 8000 chars — file is ${bytes.size} bytes]"
                   else text
        }
        return res.optString("content", "(binary file, not displayable)")
    }

    private fun listTree(token: String, repo: String, path: String?, ref: String): String {
        val url = if (path.isNullOrBlank()) {
            "$GH_API/repos/$repo/git/trees/$ref?recursive=0"
        } else {
            // Use contents API for subdir listing
            "$GH_API/repos/$repo/contents/${path.trimStart('/')}?ref=$ref"
        }
        val res = getJson(token, url)
        val items = if (res.startsWith("[")) JSONArray(res)
                    else JSONObject(res).optJSONArray("tree") ?: JSONArray()
        return buildString {
            appendLine("${repo}/${path ?: ""} (${ref}):")
            for (i in 0 until minOf(items.length(), 100)) {
                val item = items.getJSONObject(i)
                val type = item.optString("type","")
                val icon = when(type) { "tree","dir" -> "📁"; "blob","file" -> "📄"; else -> "•" }
                val name = item.optString("path", item.optString("name","?"))
                appendLine("  $icon $name")
            }
            if (items.length() > 100) appendLine("  ... ${items.length()-100} more")
        }.trim()
    }

    private fun search(token: String, query: String, repo: String?): String {
        val q = if (repo != null) "$query repo:$repo" else query
        val res = get(token, "$GH_API/search/code?q=${q.encode()}&per_page=15")
        val items = res.optJSONArray("items") ?: JSONArray()
        val total = res.optInt("total_count")
        return buildString {
            appendLine("Code search: \"$query\" — $total matches, showing ${items.length()}:")
            for (i in 0 until items.length()) {
                val it2 = items.getJSONObject(i)
                appendLine("  ${it2.optString("repository","?")?.let { (it2.getJSONObject("repository")).getString("full_name") } ?: "?"}: ${it2.getString("path")}")
            }
        }.trim()
    }

    private fun cloneRepo(token: String, repo: String, localPath: String?, ref: String): String {
        val targetDir = File(localPath ?: "/sdcard/Download/${repo.substringAfter("/")}").also { it.mkdirs() }
        val zipUrl = "https://api.github.com/repos/$repo/zipball/$ref"
        val req = Request.Builder().url(zipUrl)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/vnd.github+json")
            .build()
        var fileCount = 0
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return "Error: HTTP ${resp.code} fetching archive"
            ZipInputStream(resp.body!!.byteStream()).use { zip ->
                var entry = zip.nextEntry
                var rootPrefix: String? = null
                while (entry != null) {
                    if (rootPrefix == null) rootPrefix = entry.name.substringBefore("/") + "/"
                    val stripped = entry.name.removePrefix(rootPrefix)
                    if (stripped.isBlank()) { zip.closeEntry(); entry = zip.nextEntry; continue }
                    val dest = File(targetDir, stripped)
                    if (entry.isDirectory) dest.mkdirs()
                    else {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { zip.copyTo(it) }
                        fileCount++
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return "Cloned $repo@$ref → ${targetDir.absolutePath} ($fileCount files)"
    }

    // ── Releases ──────────────────────────────────────────────────────────────

    private fun listReleases(token: String, repo: String): String {
        val arr = parseArray(getJson(token, "$GH_API/repos/$repo/releases?per_page=10"))
        return buildString {
            appendLine("Releases for $repo:")
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val pre = if (r.optBoolean("prerelease")) " [pre]" else ""
                appendLine("  ${r.optString("tag_name","?")}$pre — ${r.optString("name","").take(60)}  (${r.optString("published_at","").take(10)})")
            }
        }.trim()
    }

    private fun createRelease(token: String, repo: String, name: String, body: String?, tag: String): String {
        val payload = JSONObject().put("tag_name", tag).put("name", name)
        if (!body.isNullOrBlank()) payload.put("body", body)
        val res = post(token, "$GH_API/repos/$repo/releases", payload)
        return "Created release ${res.optString("tag_name")}: ${res.getString("html_url")}"
    }

    // ── Projects (GraphQL) ────────────────────────────────────────────────────

    private fun listProjects(token: String, repo: String?): String {
        val query = if (repo != null) {
            val (owner, name) = repo.split("/")
            """{"query":"{ repository(owner:\"$owner\",name:\"$name\") { projectsV2(first:20){nodes{number title id updatedAt}} } }"}"""
        } else {
            """{"query":"{ viewer { projectsV2(first:20){nodes{number title id updatedAt}} } }"}"""
        }
        val res = graphql(token, query)
        val nodes = res.optJSONObject("data")
            ?.let { it.optJSONObject("repository") ?: it.optJSONObject("viewer") }
            ?.optJSONObject("projectsV2")?.optJSONArray("nodes") ?: JSONArray()
        return buildString {
            appendLine("GitHub Projects:")
            for (i in 0 until nodes.length()) {
                val p = nodes.getJSONObject(i)
                appendLine("  #${p.optInt("number")} ${p.optString("title")}  (id: ${p.optString("id")})")
            }
        }.trim()
    }

    private fun getProjectItems(token: String, projectId: String): String {
        val query = """{"query":"{ node(id:\"$projectId\") { ... on ProjectV2 { title items(first:50){nodes{content{...on Issue{number title state} ...on PullRequest{number title state}}}} } } }"}"""
        val res = graphql(token, query)
        val items = res.optJSONObject("data")?.optJSONObject("node")
            ?.optJSONObject("items")?.optJSONArray("nodes") ?: JSONArray()
        return buildString {
            appendLine("Project items:")
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i).optJSONObject("content") ?: continue
                val num = item.optInt("number")
                val ttl = item.optString("title","?")
                val st = item.optString("state","?")
                appendLine("  #$num [$st] $ttl")
            }
        }.trim()
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun getJson(token: String, url: String): String {
        val req = Request.Builder().url(url).get().auth(token).build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body!!.string()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${text.take(300)}")
            text
        }
    }

    private fun get(token: String, url: String): JSONObject =
        JSONObject(getJson(token, url))

    private fun parseArray(json: String): JSONArray =
        if (json.trimStart().startsWith("[")) JSONArray(json)
        else JSONObject(json).optJSONArray("items") ?: JSONArray()

    private fun post(token: String, url: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON_MT)).auth(token).build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body!!.string()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${text.take(300)}")
            JSONObject(text)
        }
    }

    private fun patch(token: String, url: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(url).patch(body.toString().toRequestBody(JSON_MT)).auth(token).build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body!!.string()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${text.take(300)}")
            JSONObject(text)
        }
    }

    private fun put(token: String, url: String, body: JSONObject): JSONObject {
        val req = Request.Builder().url(url).put(body.toString().toRequestBody(JSON_MT)).auth(token).build()
        return client.newCall(req).execute().use { resp ->
            val text = resp.body!!.string()
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${text.take(300)}")
            JSONObject(text)
        }
    }

    private fun graphql(token: String, body: String): JSONObject {
        val req = Request.Builder().url("https://api.github.com/graphql")
            .post(body.toRequestBody(JSON_MT)).auth(token).build()
        return client.newCall(req).execute().use { resp -> JSONObject(resp.body!!.string()) }
    }

    private fun Request.Builder.auth(token: String) = apply {
        addHeader("Authorization", "Bearer $token")
        addHeader("Accept", "application/vnd.github+json")
        addHeader("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun String.encode() = java.net.URLEncoder.encode(this, "UTF-8")
}
