package com.vela.app.ui.profile

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for the pure parsing logic in ProfileViewModel.
 * All functions under test are pure string transforms exposed via internal companion
 * so they can be verified without Android or Hilt dependencies.
 */
class ProfileViewModelTest {

    // ── frontmatter scalar fields ────────────────────────────────────────────

    @Test fun `frontmatter scalar fields extracted correctly`() {
        val md = """
            ---
            name: Alice
            role: Engineer
            location: NYC
            last_updated: 2024-01-15T00:00:00Z
            ---
            # Body
        """.trimIndent()
        val result = ProfileViewModel.parseProfileMdInternal(md)
        assertThat(result.name).isEqualTo("Alice")
        assertThat(result.role).isEqualTo("Engineer")
        assertThat(result.location).isEqualTo("NYC")
    }

    // ── list parsing: multi-line block ────────────────────────────────────────

    @Test fun `multi-line dash list parsed from frontmatter`() {
        val md = """
            ---
            name: Alice
            key_projects:
              - ProjectA
              - ProjectB
            ---
        """.trimIndent()
        val result = ProfileViewModel.parseProfileMdInternal(md)
        assertThat(result.keyProjects).containsExactly("ProjectA", "ProjectB").inOrder()
    }

    // ── list parsing: inline bracket format ──────────────────────────────────

    @Test fun `inline bracket list parsed from frontmatter`() {
        val md = """
            ---
            name: Alice
            interests: [hiking, coding]
            ---
        """.trimIndent()
        val result = ProfileViewModel.parseProfileMdInternal(md)
        assertThat(result.interests).containsExactly("hiking", "coding").inOrder()
    }

    // ── knowledge blocks ─────────────────────────────────────────────────────

    @Test fun `knowledge blocks extracted with vault name, date, and content`() {
        val md = """
            ---
            name: Alice
            ---
            <vela:knows vault="work" updated="2024-01-10">Works on Vela project</vela:knows>
        """.trimIndent()
        val result = ProfileViewModel.parseProfileMdInternal(md)
        assertThat(result.knowledgeBlocks).hasSize(1)
        assertThat(result.knowledgeBlocks[0].vaultName).isEqualTo("work")
        assertThat(result.knowledgeBlocks[0].updatedDate).isEqualTo("2024-01-10")
        assertThat(result.knowledgeBlocks[0].content).isEqualTo("Works on Vela project")
    }

    @Test fun `multiple knowledge blocks all extracted`() {
        val md = """
            ---
            name: Alice
            ---
            <vela:knows vault="work" updated="2024-01-10">Vela project</vela:knows>
            <vela:knows vault="personal" updated="2024-01-11">Side project notes</vela:knows>
        """.trimIndent()
        val result = ProfileViewModel.parseProfileMdInternal(md)
        assertThat(result.knowledgeBlocks).hasSize(2)
        assertThat(result.knowledgeBlocks.map { it.vaultName })
            .containsExactly("work", "personal").inOrder()
    }

    // ── pulse entries ─────────────────────────────────────────────────────────

    @Test fun `pulse entries extracted preserving dash prefix`() {
        val md = """
            ---
            name: Alice
            ---
            <vela:pulse>
            - 2024-01-15: Session 1
            - 2024-01-14: Session 2
            </vela:pulse>
        """.trimIndent()
        val result = ProfileViewModel.parseProfileMdInternal(md)
        assertThat(result.pulseEntries).containsExactly(
            "- 2024-01-15: Session 1",
            "- 2024-01-14: Session 2",
        ).inOrder()
    }

    @Test fun `missing pulse block returns empty list`() {
        val md = """
            ---
            name: Alice
            ---
            No pulse here.
        """.trimIndent()
        val result = ProfileViewModel.parseProfileMdInternal(md)
        assertThat(result.pulseEntries).isEmpty()
    }

    // ── malformed / missing frontmatter ───────────────────────────────────────

    @Test fun `missing frontmatter returns empty ProfileData`() {
        val result = ProfileViewModel.parseProfileMdInternal("no frontmatter here")
        assertThat(result.name).isEmpty()
        assertThat(result.role).isEmpty()
        assertThat(result.knowledgeBlocks).isEmpty()
        assertThat(result.pulseEntries).isEmpty()
    }

    // ── formatRelative ────────────────────────────────────────────────────────

    @Test fun `zero epoch formats as never`() {
        assertThat(ProfileViewModel.formatRelativeInternal(0L, now = 1_000L)).isEqualTo("never")
    }

    @Test fun `timestamp within 60 seconds formats as just now`() {
        val now = 100_000L
        val ts  = now - 30_000L
        assertThat(ProfileViewModel.formatRelativeInternal(ts, now)).isEqualTo("just now")
    }

    @Test fun `timestamp 45 minutes ago formats as Xm ago`() {
        val now = 10_000_000L
        val ts  = now - 45 * 60_000L
        assertThat(ProfileViewModel.formatRelativeInternal(ts, now)).isEqualTo("45m ago")
    }

    @Test fun `timestamp 3 hours ago formats as Xh ago`() {
        val now = 10_000_000L
        val ts  = now - 3 * 3_600_000L
        assertThat(ProfileViewModel.formatRelativeInternal(ts, now)).isEqualTo("3h ago")
    }

    @Test fun `timestamp 2 days ago formats as Xd ago`() {
        val now = 100_000_000L
        val ts  = now - 2 * 86_400_000L
        assertThat(ProfileViewModel.formatRelativeInternal(ts, now)).isEqualTo("2d ago")
    }
}
