package com.aicarcompanion.app.domain.repository

import com.aicarcompanion.app.domain.livedata.DashboardLayout
import kotlinx.coroutines.flow.Flow

/** Saved Live Data layouts, per vehicle (keyed by the app's vehicle id, never the VIN). */
interface DashboardRepository {
    /** Saved layouts for the vehicle, in the owner's order. Empty until one is saved. */
    fun layouts(vehicleId: String): Flow<List<DashboardLayout>>

    /** Id of the layout the owner last used, or null. */
    fun activeLayoutId(vehicleId: String): Flow<String?>

    /** Saves (inserts or replaces) a layout. Refuses more than [DashboardLayout.MAX_GAUGES] gauges. */
    suspend fun save(layout: DashboardLayout)
    suspend fun delete(layoutId: String)
    suspend fun setActive(vehicleId: String, layoutId: String?)
}
