package com.vela.ledger

/*
 * Item 2: "Jobs persist across app kill and simulated device reboot."
 *
 * SUBSTITUTION NOTE: the lane's canonical verification for this item is
 * `adb shell am force-stop` + relaunch against a booted emulator. This host has no
 * /dev/kvm, so no emulator can be started here (see lane spec's "Host capability
 * limits" — the same blocker already named in lane 1.1). Item 2 is therefore
 * BLOCKED-named for the on-device verification.
 *
 * As the lane spec directs, this test substitutes a JVM/Robolectric-level proof of
 * the same underlying durability guarantee: a file-backed (not in-memory) Room
 * database is written to, closed (simulating process death — no explicit flush
 * beyond normal committed transactions), and reopened as a brand new instance
 * pointed at the same file. If the record survives that cycle, the durability
 * mechanism SQLite/Room relies on (fsync'd committed transactions) is proven; what
 * is NOT proven here is Android process-lifecycle behavior itself (app kill signal
 * handling, Activity recreation, etc.), which requires a real device/emulator.
 */

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PersistenceAcrossRestartTest {

    @Test
    fun `job written before close is present after reopening a new db instance`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dbFile = File(context.cacheDir, "persistence-test-${System.nanoTime()}.db")

        val job = JobRecord(
            jobId = "job-persist",
            createdAt = 10L,
            updatedAt = 10L,
            origin = JobRecord.Origin("s", "t", "c"),
            spec = "{}",
            status = JobStatus.RUNNING,
            attention = JobRecord.Attention(false, null, emptyList(), null),
            progress = listOf(JobRecord.ProgressEntry(11L, "started", null, "fleet")),
            result = null,
            cost = JobRecord.Cost(null, null),
        )

        // First "process": write and close.
        val db1 = Room.databaseBuilder(context, LedgerDatabase::class.java, dbFile.absolutePath).build()
        SqliteLedgerRepository(db1.jobDao()).createJob(job)
        db1.close()

        // Second "process" (simulated relaunch): reopen a NEW instance at the same path.
        val db2 = Room.databaseBuilder(context, LedgerDatabase::class.java, dbFile.absolutePath).build()
        val readBack = SqliteLedgerRepository(db2.jobDao()).getJob("job-persist")
        db2.close()
        dbFile.delete()

        assertNotNull(readBack)
        assertEquals(job, readBack)
    }
}
