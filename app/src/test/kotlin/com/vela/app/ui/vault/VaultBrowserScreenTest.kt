package com.vela.app.ui.vault

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VaultBrowserScreenTest {

    @Test
    fun `detectContentType returns markdown for md extension`() {
        assertThat(detectContentType("notes/hello.md")).isEqualTo("markdown")
    }

    @Test
    fun `detectContentType returns image for jpg extension`() {
        assertThat(detectContentType("photos/image.jpg")).isEqualTo("image")
    }

    @Test
    fun `detectContentType returns image for jpeg extension`() {
        assertThat(detectContentType("photos/image.jpeg")).isEqualTo("image")
    }

    @Test
    fun `detectContentType returns image for png extension`() {
        assertThat(detectContentType("photos/image.png")).isEqualTo("image")
    }

    @Test
    fun `detectContentType returns image for webp extension`() {
        assertThat(detectContentType("photos/image.webp")).isEqualTo("image")
    }

    @Test
    fun `detectContentType returns pdf for pdf extension`() {
        assertThat(detectContentType("docs/report.pdf")).isEqualTo("pdf")
    }

    @Test
    fun `detectContentType returns json for json extension`() {
        assertThat(detectContentType("data/config.json")).isEqualTo("json")
    }

    @Test
    fun `detectContentType returns csv for csv extension`() {
        assertThat(detectContentType("data/table.csv")).isEqualTo("csv")
    }

    @Test
    fun `detectContentType returns html for html extension`() {
        assertThat(detectContentType("pages/index.html")).isEqualTo("html")
    }

    @Test
    fun `detectContentType returns html for htm extension`() {
        assertThat(detectContentType("pages/index.htm")).isEqualTo("html")
    }

    @Test
    fun `detectContentType returns text for unknown extension`() {
        assertThat(detectContentType("data/file.xyz")).isEqualTo("text")
    }

    @Test
    fun `detectContentType returns text for file with no extension`() {
        assertThat(detectContentType("Makefile")).isEqualTo("text")
    }

    @Test
    fun `detectContentType is case-insensitive for extensions`() {
        assertThat(detectContentType("photo.JPG")).isEqualTo("image")
        assertThat(detectContentType("doc.MD")).isEqualTo("markdown")
    }
}
