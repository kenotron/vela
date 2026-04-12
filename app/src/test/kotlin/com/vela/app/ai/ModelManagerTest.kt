package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ModelManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun createModelManager(): ModelManager {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        return ModelManager(
            modelsDir = tempFolder.root,
            modelUrl = server.url("/model").toString(),
            client = client,
        )
    }

    @Test
    fun initialStateIsNotDownloaded() {
        val manager = createModelManager()
        assertThat(manager.downloadState.value).isInstanceOf(DownloadState.NotDownloaded::class.java)
    }

    @Test
    fun downloadSucceedsAndReportsDownloaded() = runTest {
        val fakeModel = ByteArray(1024) { it.toByte() }
        val buffer = Buffer().write(fakeModel)
        server.enqueue(
            MockResponse()
                .setBody(buffer)
                .addHeader("Content-Length", fakeModel.size.toString()),
        )

        val manager = createModelManager()
        manager.downloadModel()

        val state = manager.downloadState.value
        assertThat(state).isInstanceOf(DownloadState.Downloaded::class.java)
        val downloaded = state as DownloadState.Downloaded
        val file = File(downloaded.path)
        assertThat(file.exists()).isTrue()
        assertThat(file.length()).isEqualTo(1024L)
    }

    @Test
    fun downloadReportsError() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val manager = createModelManager()
        manager.downloadModel()

        assertThat(manager.downloadState.value).isInstanceOf(DownloadState.Error::class.java)
    }

    @Test
    fun checkExistingModelReportsDownloaded() {
        val file = File(tempFolder.root, ModelManager.MODEL_FILENAME)
        file.writeBytes(ByteArray(1024) { 0 })

        val manager = createModelManager()
        manager.checkExistingModel()

        assertThat(manager.downloadState.value).isInstanceOf(DownloadState.Downloaded::class.java)
    }

    @Test
    fun checkExistingModelWhenAbsentRemainsNotDownloaded() {
        val manager = createModelManager()
        manager.checkExistingModel()

        assertThat(manager.downloadState.value).isInstanceOf(DownloadState.NotDownloaded::class.java)
    }
}
