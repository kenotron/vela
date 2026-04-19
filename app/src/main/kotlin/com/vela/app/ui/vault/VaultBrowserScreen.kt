package com.vela.app.ui.vault

    import android.graphics.Bitmap
    import android.graphics.BitmapFactory
    import android.graphics.Matrix
    import android.graphics.pdf.PdfRenderer
    import android.os.ParcelFileDescriptor
    import android.webkit.WebView
    import android.webkit.WebViewClient
    import androidx.compose.animation.core.*
    import androidx.compose.foundation.Image
    import androidx.compose.foundation.background
    import androidx.compose.foundation.clickable
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.lazy.LazyColumn
    import androidx.compose.foundation.lazy.items
    import androidx.compose.foundation.rememberScrollState
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.foundation.text.BasicTextField
    import androidx.compose.foundation.verticalScroll
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.automirrored.filled.ArrowBack
    import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
    import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
    import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
    import androidx.compose.material.icons.filled.*
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.draw.alpha
    import androidx.compose.ui.draw.clip
    import androidx.compose.ui.graphics.SolidColor
    import androidx.compose.ui.graphics.asImageBitmap
    import androidx.compose.ui.layout.ContentScale
    import androidx.compose.ui.platform.LocalContext
    import androidx.compose.ui.text.font.FontFamily
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.text.style.TextOverflow
    import androidx.compose.ui.text.input.TextFieldValue
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.viewinterop.AndroidView
    import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
    import androidx.compose.material3.windowsizeclass.WindowSizeClass
    import androidx.hilt.navigation.compose.hiltViewModel
    import com.vela.app.data.db.VaultEntity
    import com.vela.app.ui.components.MarkdownText
    import java.io.File
    import java.text.SimpleDateFormat
    import java.util.*

    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
    private val PDF_EXTS   = setOf("pdf")
    private val HTML_EXTS  = setOf("html", "htm")
    private val TEXT_EXTS  = setOf("md", "txt", "csv", "json", "xml", "yaml", "yml", "toml", "log")

    private fun fileIcon(entry: FileEntry): androidx.compose.ui.graphics.vector.ImageVector = when {
        entry.isDirectory             -> Icons.Default.Folder
        entry.extension in IMAGE_EXTS -> Icons.Default.Image
        entry.extension in PDF_EXTS   -> Icons.Default.PictureAsPdf
        entry.extension == "md"       -> Icons.Default.Article
        else                          -> Icons.AutoMirrored.Filled.InsertDriveFile
    }

    /** Tab-root overload — handles vault selection internally. */
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3Api::class)
    @Composable
    fun VaultBrowserScreen(
        windowSizeClass: WindowSizeClass,
        modifier:        Modifier = Modifier,
        viewModel:       VaultBrowserViewModel = hiltViewModel(),
    ) {
        val allVaults   by viewModel.allVaults.collectAsState()
        val activeVault by viewModel.activeVault.collectAsState()

        if (activeVault != null) {
            VaultBrowserScreen(
                vault      = activeVault!!,
                onBack     = { viewModel.clearVault() },
                onOpenFile = {},
            )
        } else {
            Scaffold(
                modifier = modifier,
                topBar = {
                    TopAppBar(title = { Text("Vault") })
                },
            ) { pad ->
                LazyColumn(modifier = Modifier.fillMaxSize().padding(pad)) {
                    items(allVaults) { vault ->
                        ListItem(
                            leadingContent  = { Icon(Icons.Default.Folder, contentDescription = null) },
                            headlineContent = { Text(vault.name) },
                            modifier        = Modifier.clickable { viewModel.setVault(vault) },
                        )
                    }
                    if (allVaults.isEmpty()) {
                        item {
                            ListItem(
                                headlineContent  = { Text("No vaults configured") },
                                supportingContent = { Text("Add vaults in Profile → Settings → Vaults") },
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun VaultBrowserScreen(
        vault:           VaultEntity,
        onBack:          () -> Unit,
        onOpenFile:      (String) -> Unit,   // relative path
        viewModel:       VaultBrowserViewModel = hiltViewModel(),
    ) {
        LaunchedEffect(vault.id) { viewModel.setVault(vault) }

        val entries       by viewModel.entries.collectAsState()
        val currentPath   by viewModel.currentPath.collectAsState()
        val searchResults by viewModel.searchResults.collectAsState()
        val isSearching   by viewModel.isSearching.collectAsState()
        val indexProgress by viewModel.indexProgress.collectAsState()
        val isConfigured  by viewModel.isConfigured.collectAsState()

        var query by remember { mutableStateOf("") }

        val isSearchMode = query.isNotBlank()

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            if (currentPath.isNotEmpty()) viewModel.navigateUp() else onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    title = {
                        Column {
                            Text(vault.name, style = MaterialTheme.typography.titleMedium)
                            if (currentPath.isNotEmpty()) {
                                Text(currentPath, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    actions = {
                        indexProgress?.let { (done, total) ->
                            val inf = rememberInfiniteTransition(label = "idx")
                            val alpha by inf.animateFloat(0.3f, 1f,
                                infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
                            Text("Indexing $done/$total",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.alpha(alpha).padding(end = 12.dp),
                                color = MaterialTheme.colorScheme.primary)
                        }
                    },
                )
            },
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {

                // ── Search bar ────────────────────────────────────────────────────
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    tonalElevation = 2.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Search, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("Search files…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                            BasicTextField(
                                value = query,
                                onValueChange = { q -> query = q; viewModel.search(q) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            )
                        }
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = ""; viewModel.search("") },
                                modifier = Modifier.size(20.dp)) {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                if (!isConfigured && isSearchMode) {
                    Text("Semantic search unavailable — configure a Google or OpenAI key in Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                }

                // ── Content ───────────────────────────────────────────────────────
                if (isSearchMode) {
                    // Search results
                    if (isSearching) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else if (searchResults.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No results", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)) {
                            items(searchResults, key = { "${it.filePath}:${it.chunkText.hashCode()}" }) { result ->
                                SearchResultRow(result, onClick = { onOpenFile(result.filePath) })
                            }
                        }
                    }
                } else {
                    // File tree
                    if (entries.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                            items(entries, key = { it.relativePath }) { entry ->
                                FileEntryRow(
                                    entry = entry,
                                    onClick = {
                                        if (entry.isDirectory) viewModel.navigateTo(entry.relativePath)
                                        else onOpenFile(entry.relativePath)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FileEntryRow(entry: FileEntry, onClick: () -> Unit) {
        val cs = MaterialTheme.colorScheme
        val dateStr = remember(entry.lastModified) {
            SimpleDateFormat("MMM d", Locale.US).format(Date(entry.lastModified))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(fileIcon(entry), null,
                tint = if (entry.isDirectory) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyMedium, color = cs.onSurface)
                if (!entry.isDirectory) {
                    Text("${formatSize(entry.size)} · $dateStr",
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
            if (entry.isDirectory) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = cs.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
    }

    @Composable
    private fun SearchResultRow(result: com.vela.app.vault.SearchResult, onClick: () -> Unit) {
        val cs = MaterialTheme.colorScheme
        // Split the path into breadcrumb segments
        val segments = result.filePath.replace('\\', '/').split('/')
        val title    = segments.last().substringBeforeLast('.')   // filename without extension
        val crumbs   = if (segments.size > 1)
            (listOf(result.vaultName.ifBlank { "vault" }) + segments.dropLast(1))
                .joinToString(" › ")
        else result.vaultName.ifBlank { "vault" }

        Column(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            // Path breadcrumb — small, muted, like a search-result URL
            Text(
                crumbs,
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // File name as the "title" link
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
                color = cs.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            // Relevant text snippet
            Text(
                result.chunkText.trim().take(240),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurface.copy(alpha = 0.75f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 20.dp),
            color = cs.outlineVariant.copy(alpha = 0.4f),
        )
    }

    // ─── File viewer ──────────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun VaultFileViewerScreen(
        vault:    VaultEntity,
        relPath:  String,
        onBack:   () -> Unit,
    ) {
        val file = remember(vault.localPath, relPath) { File(vault.localPath, relPath) }
        val ext  = relPath.substringAfterLast('.', "").lowercase()

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                    title = { Text(file.name, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                )
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when {
                    ext == "md"          -> MarkdownViewer(file)
                    ext in IMAGE_EXTS    -> ImageViewer(file)
                    ext in PDF_EXTS      -> PdfViewer(file)
                    ext in HTML_EXTS     -> HtmlViewer(file)
                    ext in TEXT_EXTS     -> PlainTextViewer(file)
                    else                 -> PlainTextViewer(file)
                }
            }
        }
    }

    @Composable
    private fun MarkdownViewer(file: File) {
        val text = remember(file.absolutePath) {
            runCatching { file.readText() }.getOrElse { "Error reading file: ${it.message}" }
        }
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp)) {
            MarkdownText(text = text, color = MaterialTheme.colorScheme.onSurface)
        }
    }

    @Composable
    private fun PlainTextViewer(file: File) {
        val text = remember(file.absolutePath) {
            runCatching { file.readText() }.getOrElse { "Error reading file: ${it.message}" }
        }
        val scroll = rememberScrollState()
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        )
    }

    @Composable
    private fun ImageViewer(file: File) {
        val bitmap = remember(file.absolutePath) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            runCatching { BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap() }.getOrNull()
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(8.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Could not decode image", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    @Composable
    private fun PdfViewer(file: File) {
        var currentPage by remember { mutableIntStateOf(0) }

        // Open PdfRenderer — close on leave
        val pair = remember(file.absolutePath) {
            runCatching {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd to PdfRenderer(pfd)
            }.getOrNull()
        }
        DisposableEffect(file.absolutePath) {
            onDispose { pair?.second?.close(); pair?.first?.close() }
        }

        if (pair == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Could not open PDF", color = MaterialTheme.colorScheme.error)
            }
            return
        }

        val renderer  = pair.second
        val pageCount = renderer.pageCount

        val bitmap = remember(currentPage) {
            runCatching {
                val page  = renderer.openPage(currentPage)
                val scale = 2f
                val bmp   = Bitmap.createBitmap(
                    (page.width  * scale).toInt(),
                    (page.height * scale).toInt(),
                    Bitmap.Config.ARGB_8888,
                )
                page.render(bmp, null, Matrix().also { it.setScale(scale, scale) },
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bmp.asImageBitmap()
            }.getOrNull()
        }

        Column(Modifier.fillMaxSize()) {
            // Page nav bar
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (currentPage > 0) currentPage-- },
                    enabled = currentPage > 0) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous")
                }
                Text("${currentPage + 1} / $pageCount",
                    style = MaterialTheme.typography.bodyMedium)
                IconButton(onClick = { if (currentPage < pageCount - 1) currentPage++ },
                    enabled = currentPage < pageCount - 1) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next")
                }
            }
            HorizontalDivider()
            // Page image
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                if (bitmap != null) {
                    Image(bitmap = bitmap, contentDescription = "Page ${currentPage + 1}",
                        modifier = Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                } else {
                    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    @Composable
    private fun HtmlViewer(file: File) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = false
                    loadUrl("file://${file.absolutePath}")
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1_024             -> "${bytes}B"
        bytes < 1_048_576         -> "${"%.1f".format(bytes / 1_024.0)}KB"
        else                      -> "${"%.1f".format(bytes / 1_048_576.0)}MB"
    }
    