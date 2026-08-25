package com.vela.core.domain.notification

import com.vela.core.domain.LedgerRepository.LedgerEntry

/**
 * Notification gate (#47, epic #19): "progress updates never notify" as a
 * construction-level guarantee, not a filter applied at render/dispatch time.
 *
 * [AttentionCandidate] is the ONLY type this notifier's input can be. It has a
 * private constructor, so the sole way to obtain one is [AttentionCandidate.from],
 * which returns null for any [LedgerEntry] with `requiresAttention == false`.
 * There is no code path -- not a bypassable `if`, not a caller mistake -- by
 * which a progress-only entry can ever become an [AttentionCandidate] and reach
 * [AttentionNotifier.notify]. A progress entry is structurally incapable of
 * reaching the notifier: [AttentionNotifier.notify] does not even accept a
 * [LedgerEntry], it accepts an [AttentionCandidate], and constructing one from
 * a non-attention entry is impossible.
 */
class AttentionCandidate private constructor(val entry: LedgerEntry) {

    companion object {
        /**
         * The only entry point into this type. Returns null when [entry] does not
         * require attention -- there is no other way to obtain an
         * [AttentionCandidate] for such an entry.
         */
        fun from(entry: LedgerEntry): AttentionCandidate? =
            if (entry.requiresAttention) AttentionCandidate(entry) else null
    }
}

/** A single delivered notification, keyed to the entry it was raised for. */
data class AttentionNotification(
    val entryId: String,
    val title: String,
    val summary: String,
)

/**
 * Sink for delivering an attention notification once an [AttentionCandidate]
 * has been produced. Implementations (platform notification manager, in-app
 * banner, test recorder, etc.) plug in here; none of them can be handed
 * anything but an [AttentionCandidate].
 */
fun interface AttentionNotificationSink {
    fun deliver(notification: AttentionNotification)
}

/**
 * Emits notifications for [AttentionCandidate]s only. Because the input type
 * ([AttentionCandidate]) can only be constructed from an entry with
 * `requiresAttention == true` (see [AttentionCandidate.from]), this class has
 * no way to notify for a progress-only entry -- the guarantee lives in the
 * type signature of [notify], not in a runtime check performed here.
 */
class AttentionNotifier(private val sink: AttentionNotificationSink) {

    /**
     * Deliver a notification for [candidate]. There is no overload, and no
     * other public method, that accepts a bare [LedgerEntry] -- callers must
     * first obtain an [AttentionCandidate] via [AttentionCandidate.from], which
     * is where the attention-required guarantee is enforced.
     */
    fun notify(candidate: AttentionCandidate) {
        val entry = candidate.entry
        sink.deliver(
            AttentionNotification(
                entryId = entry.id,
                title = entry.title,
                summary = entry.summary,
            ),
        )
    }
}
