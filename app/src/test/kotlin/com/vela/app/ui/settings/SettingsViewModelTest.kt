package com.vela.app.ui.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the URL normalization logic that converts username/repo shorthand
 * into full GitHub HTTPS URLs. normalizeRemoteUrl is a pure function exposed as
 * an internal companion member so it can be verified without Android dependencies.
 */
class SettingsViewModelTest {

    @Test fun `username slash repo shorthand normalized to full GitHub https URL`() {
        val result = SettingsViewModel.normalizeRemoteUrl("ken/my-vault")
        assertThat(result).isEqualTo("https://github.com/ken/my-vault.git")
    }

    @Test fun `username slash repo with dots normalized to full GitHub https URL`() {
        val result = SettingsViewModel.normalizeRemoteUrl("org-name/repo.name")
        assertThat(result).isEqualTo("https://github.com/org-name/repo.name.git")
    }

    @Test fun `full https URL passes through unchanged`() {
        val result = SettingsViewModel.normalizeRemoteUrl("https://github.com/ken/my-vault.git")
        assertThat(result).isEqualTo("https://github.com/ken/my-vault.git")
    }

    @Test fun `http URL passes through unchanged`() {
        val result = SettingsViewModel.normalizeRemoteUrl("http://gitlab.com/ken/my-vault.git")
        assertThat(result).isEqualTo("http://gitlab.com/ken/my-vault.git")
    }

    @Test fun `git ssh URL passes through unchanged`() {
        val result = SettingsViewModel.normalizeRemoteUrl("git@github.com:ken/my-vault.git")
        assertThat(result).isEqualTo("git@github.com:ken/my-vault.git")
    }

    @Test fun `blank input returns empty string`() {
        val result = SettingsViewModel.normalizeRemoteUrl("   ")
        assertThat(result).isEqualTo("")
    }

    @Test fun `leading and trailing whitespace is trimmed from shorthand`() {
        val result = SettingsViewModel.normalizeRemoteUrl("  ken/my-vault  ")
        assertThat(result).isEqualTo("https://github.com/ken/my-vault.git")
    }
}
