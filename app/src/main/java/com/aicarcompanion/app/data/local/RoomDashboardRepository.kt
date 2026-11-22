package com.aicarcompanion.app.data.local

import com.aicarcompanion.app.domain.livedata.DashboardLayout
import com.aicarcompanion.app.domain.livedata.GaugeSize
import com.aicarcompanion.app.domain.livedata.GaugeSpec
import com.aicarcompanion.app.domain.livedata.GaugeStyle
import com.aicarcompanion.app.domain.livedata.UnitChoice
import com.aicarcompanion.app.domain.repository.DashboardRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Room-backed layouts. Keyed by the app's vehicle id; layout names are never logged. */
class RoomDashboardRepository(
    private val dao: DashboardDao,
    private val now: () -> Long = System::currentTimeMillis,
) : DashboardRepository {

    override fun layouts(vehicleId: String): Flow<List<DashboardLayout>> =
        combine(dao.observeLayouts(vehicleId), dao.observeGauges(vehicleId)) { layouts, gauges ->
            val byLayout = gauges.groupBy { it.layoutId }
            layouts.map { l ->
                DashboardLayout(l.id, l.vehicleId, l.name, byLayout[l.id].orEmpty().sortedBy { it.slot }.mapNotNull { it.toSpec() })
            }
        }

    override fun activeLayoutId(vehicleId: String): Flow<String?> = dao.observeActive(vehicleId).distinctUntilChanged()

    override suspend fun save(layout: DashboardLayout) {
        val time = now()
        val existing = dao.find(layout.id)
        val entity = DashboardLayoutEntity(
            layout.id, layout.vehicleId, layout.name,
            position = existing?.position ?: dao.layoutCount(layout.vehicleId),
            createdAtMillis = existing?.createdAtMillis ?: time,
            updatedAtMillis = time,
        )
        dao.saveLayout(
            entity,
            layout.gauges.mapIndexed { slot, g -> DashboardGaugeEntity(layout.id, slot, g.pid, g.style.name, g.size.name, g.unit?.name) },
        )
    }

    override suspend fun delete(layoutId: String) {
        dao.forgetActive(layoutId)
        dao.deleteLayout(layoutId)
    }

    override suspend fun setActive(vehicleId: String, layoutId: String?) {
        dao.upsertPrefs(DashboardPrefsEntity(vehicleId, layoutId))
    }

    private fun DashboardGaugeEntity.toSpec(): GaugeSpec? {
        // A row written by a future or damaged version is skipped rather than guessed at.
        val style = GaugeStyle.entries.firstOrNull { it.name == style } ?: return null
        val size = GaugeSize.entries.firstOrNull { it.name == size } ?: return null
        return GaugeSpec(pid, style, size, unit?.let { u -> UnitChoice.entries.firstOrNull { it.name == u } })
    }
}
