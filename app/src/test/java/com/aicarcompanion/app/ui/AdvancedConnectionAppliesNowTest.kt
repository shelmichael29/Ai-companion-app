package com.aicarcompanion.app.ui

import com.aicarcompanion.app.data.local.AppSettings
import com.aicarcompanion.app.data.local.ExplanationLevel
import com.aicarcompanion.app.data.local.InMemorySettingsStore
import com.aicarcompanion.app.data.local.MapKeyValueStore
import com.aicarcompanion.app.diagnostics.obd.ObdAdvancedControls
import com.aicarcompanion.app.diagnostics.obd.ObdConfigStore
import com.aicarcompanion.app.diagnostics.obd.ObdConnection
import com.aicarcompanion.app.ui.adapter.AdvancedConnectionViewModel
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

/** Custom connection settings apply at DIY, never Everyday (owner decision 2026-11-25, Phase 1B; supersedes the Phase 1A interim rule). */
class AdvancedConnectionAppliesNowTest {
    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> error("not used") } as T

    private fun applies(level: ExplanationLevel) = AdvancedConnectionViewModel(
        ObdConfigStore(MapKeyValueStore()), unused<ObdAdvancedControls>(), unused<ObdConnection>(),
        InMemorySettingsStore(AppSettings(explanationLevel = level)),
    ).appliesNow

    @Test fun `appliesNow is false at Everyday and true at DIY and the internal Advanced level`() {
        assertEquals(false, applies(ExplanationLevel.EVERYDAY))
        assertEquals(true, applies(ExplanationLevel.DIY))
        assertEquals(true, applies(ExplanationLevel.ADVANCED))
    }
}
