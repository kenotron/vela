package com.vela.app.server

    import android.content.Context
    import android.content.res.AssetManager
    import com.vela.app.ai.AmplifierSession
    import com.vela.app.events.EventBus
    import com.vela.app.vault.VaultRegistry
    import io.ktor.http.*
    import io.ktor.server.application.*
    import io.ktor.server.request.*
    import io.ktor.server.response.*
    import io.ktor.server.routing.*
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext
    import org.commonmark.node.*
    import org.commonmark.parser.Parser
    import org.json.JSONArray
    import org.json.JSONObject
    import java.io.File
    import javax.inject.Inject
    import javax.inject.Singleton

    @Singleton
    class VelaMiniAppRoutes @Inject constructor(
        private val vaultRegistry: VaultRegistry,
        private val amplifierSession: AmplifierSession,
        @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
        private val eventBus: EventBus,
    ) {
        // In-memory event ring buffer — max 100 events, thread-safe via synchronized
        private val eventBuffer = ArrayDeque<Triple<String, String, Long>>()

        fun install(app: Application) {
            app.routing {

                get("/health") {
                    call.respondText(
                        JSONObject().put("ok", true).toString(),
                        ContentType.Application.Json
                    )
                }

                // Serve renderer HTML
                get("/miniapps/{contentType}") {
                    val type = call.parameters["contentType"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing contentType")
                    val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                        ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, "No vault configured")
                    val file = File(vault.localPath, ".vela/renderers/$type/renderer.html")
                    if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound, "No renderer for $type")
                    call.respondText(withContext(Dispatchers.IO) { file.readText() }, ContentType.Text.Html)
                }

                // Delete renderer (force fresh generation)
                delete("/miniapps/{contentType}") {
                    val type = call.parameters["contentType"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest)
                    val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                        ?: return@delete call.respond(HttpStatusCode.ServiceUnavailable)
                    withContext(Dispatchers.IO) {
                        File(vault.localPath, ".vela/renderers/$type/renderer.html").delete()
                    }
                    call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
                }

                // Vault read — raw text or parsed JSON
                get("/api/vault/read") {
                    val path = call.request.queryParameters["path"]
                        ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing path")
                    val format = call.request.queryParameters["format"] ?: "raw"
                    val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                        ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
                    val file = File(vault.localPath, path)
                    if (!file.exists()) return@get call.respond(HttpStatusCode.NotFound)
                    val content = withContext(Dispatchers.IO) { file.readText() }
                    if (format == "json") {
                        call.respondText(parseMarkdownToJson(content).toString(), ContentType.Application.Json)
                    } else {
                        call.respondText(content, ContentType.Text.Plain)
                    }
                }

                // Vault directory list
                get("/api/vault/list") {
                    val path = call.request.queryParameters["path"] ?: ""
                    val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                        ?: return@get call.respond(HttpStatusCode.ServiceUnavailable)
                    val dir = File(vault.localPath, path)
                    val arr = JSONArray()
                    withContext(Dispatchers.IO) {
                        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                            arr.put(JSONObject().apply {
                                put("name", f.name)
                                put("path", f.relativeTo(File(vault.localPath)).path)
                                put("isDir", f.isDirectory)
                                put("size", if (f.isFile) f.length() else 0)
                            })
                        }
                    }
                    call.respondText(arr.toString(), ContentType.Application.Json)
                }

                // AI complete — single synchronous turn
                post("/api/ai/complete") {
                    val body = JSONObject(call.receiveText())
                    val prompt = body.getString("prompt")
                    val systemPrompt = body.optString("systemPrompt", "You are a helpful assistant.")
                    val sb = StringBuilder()
                    amplifierSession.runTurn(
                        historyJson       = "[]",
                        userInput         = prompt,
                        userContentJson   = null,
                        systemPrompt      = systemPrompt,
                        onToolStart       = { _, _ -> "" },
                        onToolEnd         = { _, _ -> },
                        onToken           = { token -> sb.append(token) },
                        onProviderRequest = { null },
                        onServerTool      = { _, _ -> },
                    )
                    call.respondText(
                        JSONObject().put("text", sb.toString()).toString(),
                        ContentType.Application.Json
                    )
                }

                // Events emit
                post("/api/events/emit") {
                    val body = JSONObject(call.receiveText())
                    synchronized(eventBuffer) {
                        if (eventBuffer.size >= 100) eventBuffer.removeFirst()
                        eventBuffer.addLast(Triple(
                            body.getString("name"),
                            body.optString("data", "{}"),
                            System.currentTimeMillis()
                        ))
                    }
                    call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
                }

                // Events poll
                get("/api/events/poll") {
                    val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                    val arr = JSONArray()
                    synchronized(eventBuffer) {
                        eventBuffer.filter { it.third > since }.forEach { (name, data, ts) ->
                            arr.put(JSONObject().put("name", name).put("data", data).put("ts", ts))
                        }
                    }
                    call.respondText(arr.toString(), ContentType.Application.Json)
                }

                // Serve assets: Lit library + vela-runtime
                get("/lib/{filename}") {
                    val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    try {
                        val bytes = withContext(Dispatchers.IO) { appContext.assets.open("lib/$filename").readBytes() }
                        val ct = if (filename.endsWith(".js")) ContentType("application", "javascript") else ContentType.Application.OctetStream
                        call.respondBytes(bytes, ct)
                    } catch (e: java.io.FileNotFoundException) { call.respond(HttpStatusCode.NotFound) }
                }

                // Serve Lit Web Component blocks
                get("/blocks/{filename}") {
                    val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    try {
                        val bytes = withContext(Dispatchers.IO) { appContext.assets.open("blocks/$filename").readBytes() }
                        call.respondBytes(bytes, ContentType("application", "javascript"))
                    } catch (e: java.io.FileNotFoundException) { call.respond(HttpStatusCode.NotFound) }
                }

                // Serve skill template files
                get("/skills/{skill}/{filename}") {
                    val skill    = call.parameters["skill"]    ?: return@get call.respond(HttpStatusCode.BadRequest)
                    val filename = call.parameters["filename"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                    try {
                        val bytes = withContext(Dispatchers.IO) { appContext.assets.open("skills/$skill/$filename").readBytes() }
                        val ct = if (filename.endsWith(".html")) ContentType.Text.Html else ContentType.Text.Plain
                        call.respondBytes(bytes, ct)
                    } catch (e: java.io.FileNotFoundException) { call.respond(HttpStatusCode.NotFound) }
                }

                // PUT /miniapps/{contentType} — overwrite renderer + signal reload (Remix mode)
                put("/miniapps/{contentType}") {
                    val type  = call.parameters["contentType"] ?: return@put call.respond(HttpStatusCode.BadRequest)
                    val vault = vaultRegistry.enabledVaults.value.firstOrNull() ?: return@put call.respond(HttpStatusCode.ServiceUnavailable)
                    val html  = call.receiveText()
                    withContext(Dispatchers.IO) {
                        val dir = java.io.File(vault.localPath, ".vela/renderers/$type")
                        dir.mkdirs()
                        java.io.File(dir, "renderer.html").writeText(html)
                    }
                    eventBus.tryPublish("renderer:updated", type)
                    call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
                }

                // Write vault file
                post("/api/vault/write") {
                    val body    = JSONObject(call.receiveText())
                    val path    = body.getString("path")
                    val content = body.getString("content")
                    val vault   = vaultRegistry.enabledVaults.value.firstOrNull() ?: return@post call.respond(HttpStatusCode.ServiceUnavailable)
                    withContext(Dispatchers.IO) {
                        val file = java.io.File(vault.localPath, path)
                        file.parentFile?.mkdirs()
                        file.writeText(content)
                    }
                    call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
                }

                // App operations — fire into EventBus, native Compose listens
                post("/api/app/navigate") {
                    val body = call.receiveText()
                    eventBus.tryPublish("app:navigate", runCatching { JSONObject(body).optString("relPath", "") }.getOrElse { body })
                    call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
                }
                post("/api/app/notify") {
                    eventBus.tryPublish("app:notify", call.receiveText())
                    call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
                }
                post("/api/app/refresh") {
                    call.receiveText()
                    eventBus.tryPublish("app:refresh", "")
                    call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
                }
                post("/api/app/remix") {
                    call.receiveText()
                    eventBus.tryPublish("app:remix-requested", "")
                    call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
                }
                post("/api/app/record") {
                    eventBus.tryPublish("app:record-start", call.receiveText())
                    call.respondText(JSONObject().put("ok", true).toString(), ContentType.Application.Json)
                }
            }
        }

        private fun parseMarkdownToJson(markdown: String): JSONObject {
            val parser = Parser.builder().build()
            val doc = parser.parse(markdown)
            val sections = JSONArray()
            val frontmatter = JSONObject()

            val fmRegex = Regex("""^---\n([\s\S]*?)\n---""", RegexOption.MULTILINE)
            fmRegex.find(markdown)?.groupValues?.getOrNull(1)?.lines()?.forEach { line ->
                if (line.contains(":")) {
                    val idx = line.indexOf(":")
                    frontmatter.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
                }
            }

            var node: Node? = doc.firstChild
            while (node != null) {
                val obj = JSONObject()
                when (node) {
                    is Heading -> {
                        obj.put("type", "heading")
                        obj.put("level", node.level)
                        obj.put("text", collectText(node.firstChild))
                    }
                    is Paragraph -> {
                        obj.put("type", "paragraph")
                        obj.put("text", collectText(node.firstChild))
                    }
                    is BulletList, is OrderedList -> {
                        obj.put("type", "list")
                        val items = JSONArray()
                        var item = node.firstChild
                        while (item != null) {
                            items.put(collectText(item.firstChild?.firstChild))
                            item = item.next
                        }
                        obj.put("items", items)
                    }
                    is FencedCodeBlock -> {
                        obj.put("type", "code")
                        obj.put("text", node.literal)
                    }
                    else -> { node = node.next; continue }
                }
                sections.put(obj)
                node = node.next
            }
            return JSONObject().put("frontmatter", frontmatter).put("sections", sections)
        }

        private fun collectText(node: Node?): String {
            var n = node
            val sb = StringBuilder()
            while (n != null) {
                when (n) {
                    is Text -> sb.append(n.literal)
                    is SoftLineBreak -> sb.append(" ")
                    is HardLineBreak -> sb.append("\n")
                    else -> if (n.firstChild != null) sb.append(collectText(n.firstChild))
                }
                n = n.next
            }
            return sb.toString()
        }
    }
