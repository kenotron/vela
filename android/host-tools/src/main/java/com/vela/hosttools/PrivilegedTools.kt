package com.vela.hosttools

/**
 * Privileged-tool classification (#45).
 *
 * A tool is privileged when invoking it has a real-world, hard-to-undo, or
 * credentialed effect on something outside the app's own sandbox -- as
 * opposed to a pure local read. Privileged tools MUST pass through
 * [ApprovalGate] before they execute (#44/#57).
 *
 * | Tool              | Privileged | Why |
 * |-------------------|------------|-----|
 * | `calendar_read`   | no  | read-only, no mutation |
 * | `calendar_create` | yes | mutates the user's real device calendar |
 * | `calendar_modify` | yes | mutates the user's real device calendar |
 * | `notes_create`    | no  | writes to the app's own local sandboxed SQLite db, low blast radius |
 * | `reminders_*`     | no  | local sandboxed app data, low blast radius |
 * | `dispatch_to_fleet` | yes | runs with real fleet credentials against real machines -- the exact gap named in #57: "larger authority than F2 protects" |
 *
 * Classification is intentionally done by tool **name**, in a table this
 * lane owns, rather than by having every tool implementation declare its own
 * privilege level. This lets the gate cover tools defined in files this lane
 * does not own -- e.g. `dispatch_to_fleet` in `DispatchToFleetTool.kt`
 * (owned by the sibling `fleet-plane` lane) -- with zero edits to that file.
 * Wiring a *new* privileged tool in the future is a one-line addition to
 * [PRIVILEGED] plus a row in the table above; it does not require touching
 * the tool's own source.
 */
object PrivilegedTools {

    /** Tool names that require human approval before executing. */
    val PRIVILEGED: Set<String> = setOf(
        "calendar_create",
        "calendar_modify",
        "dispatch_to_fleet",
    )

    /** True if [toolName] requires approval before it may execute. */
    fun isPrivileged(toolName: String): Boolean = toolName in PRIVILEGED
}
