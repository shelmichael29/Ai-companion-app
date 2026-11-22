package com.aicarcompanion.app.data.memory

import com.aicarcompanion.app.domain.livedata.DashboardLayout
import com.aicarcompanion.app.domain.repository.DashboardRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/** Non-persistent [DashboardRepository], for tests and previews. */
class InMemoryDashboardRepository : DashboardRepository {
    private val layouts = MutableStateFlow<List<DashboardLayout>>(emptyList())
    private val active = MutableStateFlow<Map<String, String?>>(emptyMap())

    override fun layouts(vehicleId: String): Flow<List<DashboardLayout>> = layouts.map { l -> l.filter { it.vehicleId == vehicleId } }
    override fun activeLayoutId(vehicleId: String): Flow<String?> = active.map { it[vehicleId] }

    override suspend fun save(layout: DashboardLayout) {
        layouts.update { list -> if (list.any { it.id == layout.id }) list.map { if (it.id == layout.id) layout else it } else list + layout }
    }

    override suspend fun delete(layoutId: String) {
        layouts.update { l -> l.filterNot { it.id == layoutId } }
        active.update { m -> m.mapValues { (_, v) -> v?.takeIf { it != layoutId } } }
    }

    override suspend fun setActive(vehicleId: String, layoutId: String?) {
        active.update { it + (vehicleId to layoutId) }
    }
}
