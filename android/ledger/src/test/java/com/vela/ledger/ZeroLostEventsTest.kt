package com.vela.ledger

/*
 * Item 5: "Zero lost events (G1) passes for the local-only case."
 *
 * SUBSTITUTION NOTE: the lane's canonical verification is a real `adb shell am
 * force-stop` mid-sequence followed by relaunch on a booted emulator/device. This
 * host has no /dev/kvm, so no emulator can be started (same blocker named for item
 * 2 and already recorded in lane 1.1). Item 5 is BLOCKED-named for the on-device
 * verification.
 *
 * As directed by the lane spec, this test substitutes: writes a known-cardinality
 * sequence of job creates/updates to a file-backed (not in-memory) Room database,
 * where each write is its own committed transaction (Room/SQLite fsyncs on commit —
 * this is the same durability primitive a real force-stop would test, since
 * `force-stop` kills the process but does not corrupt already-committed SQLite
 * transactions). The database connection is then closed WITHOUT any extra
 * flush/checkpoint step beyond ordinary commit semantics, a NEW LedgerDatabase
 * instance is opened at the same path (simulating relaunch), and the final
 * read-back state is asserted to match exactly the expected post-write state for
 * every job — no dropped or corrupted records.
 */

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ZeroLostEventsTest {

    @Test
    fun `known cardinality sequence survives close and reopen with no lost or corrupted records`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val dbFile = File(context.cacheDir, "zero-lost-events-${System.nanoTime()}.db")

        val jobCount = 10

        // "Process A": create N jobs, then update every even-indexed job, then force-close
        // mid-way through simulating a kill after the N creates + partial updates.
        val db1 = Room.databaseBuilder(context, LedgerDatabase::class.java, dbFile.absolutePath).build()
        val repo1 = SqliteLedgerRepository(db1.jobDao())

        val expected = mutableMapOf<String, JobStatus>()
        for (i in 0 until jobCount) {
            val id = "job-$i"
            repo1.createJob(
                JobRecord(
                    jobId = id,
                    createdAt = i.toLong(),
                    updatedAt = i.toLong(),
                    origin = JobRecord.Origin("s", "t", "c-$i"),
                    spec = "{}",
                    status = JobStatus.ACCEPTED,
                    attention = JobRecord.Attention(false, null, emptyList(), null),
                    progress = emptyList(),
                    result = null,
                    cost = JobRecord.Cost(null, null),
                )
            )
            expected[id] = JobStatus.ACCEPTED
        }

        // Update every even job to RUNNING - each is a separate committed transaction.
        for (i in 0 until jobCount step 2) {
            val id = "job-$i"
            repo1.updateStatus(id, JobStatus.RUNNING, updatedAt = 100L + i)
            expected[id] = JobStatus.RUNNING
        }

        // Simulate force-kill: close with no extra flush beyond normal commits.
        db1.close()

        // "Process B" (relaunch): brand new instance at the same file path.
        val db2 = Room.databaseBuilder(context, LedgerDatabase::class.java, dbFile.absolutePath).build()
        val repo2 = SqliteLedgerRepository(db2.jobDao())

        val all = repo2.observeAll().first()
        db2.close()
        dbFile.delete()

        // Exact cardinality: no dropped, no duplicated, no extra records.
        assertEquals(jobCount, all.size)

        val actualStatusById = all.associate { it.jobId to it.status }
        assertEquals(expected, actualStatusById)
    }
}
