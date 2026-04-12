package com.vela.app.a2ui

/**
 * Vela-UI — compact A2UI v0.8-inspired component model optimised for Gemma 4 E2B (nano-fast).
 *
 * A2UI spec: https://a2ui.org/specifications/v0.8/
 * Official Jetpack Compose A2UI renderer is planned for Q2 2025.
 * This custom renderer provides structural parity until that ships, at which point
 * component mapping is straightforward (Card→Card, Step→OrderedItem, etc.).
 *
 * The JSON schema keeps keys short (t, n) so Gemma 4 nano can generate it reliably
 * within the 4000-token preview limit.
 */
data class VelaUiPayload(val components: List<VelaUiComponent>)

sealed class VelaUiComponent {
    /**
     * A2UI: Card — section header with optional subtitle.
     * Renders as a filled card surface above its sibling components.
     */
    data class Card(val title: String, val subtitle: String? = null) : VelaUiComponent()

    /**
     * A2UI: OrderedItem — numbered instruction step.
     * Collects into a visual step sequence in the renderer.
     */
    data class Step(val n: Int, val text: String) : VelaUiComponent()

    /**
     * A2UI: BulletItem — unordered list entry.
     */
    data class Item(val text: String) : VelaUiComponent()

    /**
     * A2UI: Text — body paragraph for plain prose responses.
     */
    data class BodyText(val text: String) : VelaUiComponent()

    /**
     * A2UI: Text with "note" variant — highlighted tip, warning, or callout.
     */
    data class Tip(val text: String) : VelaUiComponent()

    /**
     * A2UI: Code — monospace code block.
     */
    data class Code(val text: String, val lang: String? = null) : VelaUiComponent()
}
