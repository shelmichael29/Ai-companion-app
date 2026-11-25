package com.aicarcompanion.app.dashboard

import com.aicarcompanion.app.data.local.ExplanationLevel

/**
 * Home-screen layout, chosen separately from the knowledge level.
 *
 * - [EASY]: the app picks and orders the shortcuts for the current knowledge level.
 * - [CUSTOMIZABLE]: the user picks and orders them.
 *
 * Each knowledge level keeps its own layout, so switching level never erases a customization.
 */
enum class HomeLayout(val label: String, val description: String) {
    EASY("Easy", "The app arranges your home screen for you."),
    CUSTOMIZABLE("Customizable", "Choose which shortcuts appear and in what order."),
}

/** Which dashboard experience a knowledge level gets. The orb is on all three; only its prominence changes. */
enum class DashboardKind(val orbScale: Float) {
    /** Everyday Driver: voice-first companion, large orb. */
    COMPANION(1f),
    /** DIY Enthusiast: gauges and guided diagnostics, medium orb. */
    WORKSHOP(0.78f),
    /** Advanced / Professional: telemetry and technical tools, compact orb. */
    TELEMETRY(0.58f),
    ;

    companion object {
        fun forLevel(level: ExplanationLevel) = when (level) {
            ExplanationLevel.EVERYDAY -> COMPANION
            ExplanationLevel.DIY -> WORKSHOP
            ExplanationLevel.ADVANCED -> TELEMETRY
        }
    }
}

/**
 * A shortcut that can appear on the home screen.
 *
 * @param minLevel the lowest knowledge level the shortcut is offered at.
 * @param required can be moved but never hidden. Car Health stays visible so a warning is never
 *   hidden by a customization; Extras stays so optional tools remain discoverable.
 * @param optional an Extras tool: never on the default home screen, only if the user adds it.
 */
enum class DashboardItem(
    val label: String,
    val description: String,
    val minLevel: ExplanationLevel = ExplanationLevel.EVERYDAY,
    val required: Boolean = false,
    val optional: Boolean = false,
) {
    CAR_HEALTH("Car Health", "Warning codes and what they mean", required = true),
    MY_VEHICLE("My Vehicle", "Your garage and vehicle details"),
    HISTORY("History", "Service records and past code reads"),
    SETTINGS("Settings", "Voice, appearance and app settings"),
    ADAPTER("Adapter", "Connect and troubleshoot your OBD-II adapter", minLevel = ExplanationLevel.DIY),
    CONNECTION_SETTINGS(
        "Connection", "Protocol, timing, adapter test and communication log",
        minLevel = ExplanationLevel.DIY,
    ),
    MILEAGE_LOG("Mileage log", "Record odometer readings", optional = true),
    EXTRAS("Extras", "Optional tools", required = true),
    ;

    fun availableAt(level: ExplanationLevel) = level.ordinal >= minLevel.ordinal
}

/** One knowledge level's home-screen choice. [customItems] is only used in [HomeLayout.CUSTOMIZABLE]. */
data class LevelLayout(
    val layout: HomeLayout = HomeLayout.EASY,
    val customItems: List<DashboardItem>? = null,
)

/** Home-screen layouts for every knowledge level. Missing levels use the defaults. */
data class DashboardSettings(val byLevel: Map<ExplanationLevel, LevelLayout> = emptyMap()) {
    fun forLevel(level: ExplanationLevel): LevelLayout = byLevel[level] ?: LevelLayout()
    fun with(level: ExplanationLevel, layout: LevelLayout) = copy(byLevel = byLevel + (level to layout))
}

/** Decides what the home screen shows. Pure logic, unit-tested. */
object DashboardPlanner {

    /** The recommended arrangement for each level ([HomeLayout.EASY]). Never includes optional Extras tools. */
    fun defaults(level: ExplanationLevel): List<DashboardItem> = when (level) {
        ExplanationLevel.EVERYDAY -> listOf(
            DashboardItem.MY_VEHICLE, DashboardItem.CAR_HEALTH, DashboardItem.HISTORY, DashboardItem.SETTINGS, DashboardItem.EXTRAS,
        )
        ExplanationLevel.DIY -> listOf(
            DashboardItem.CAR_HEALTH, DashboardItem.MY_VEHICLE, DashboardItem.ADAPTER, DashboardItem.HISTORY,
            DashboardItem.SETTINGS, DashboardItem.EXTRAS,
        )
        ExplanationLevel.ADVANCED -> listOf(
            DashboardItem.CAR_HEALTH, DashboardItem.ADAPTER, DashboardItem.CONNECTION_SETTINGS, DashboardItem.MY_VEHICLE,
            DashboardItem.HISTORY, DashboardItem.SETTINGS, DashboardItem.EXTRAS,
        )
    }

    /** Every shortcut the user may choose from at [level], in a stable order. */
    fun choices(level: ExplanationLevel): List<DashboardItem> = DashboardItem.entries.filter { it.availableAt(level) }

    /**
     * The shortcuts to show, in order.
     *
     * A custom list is cleaned up rather than trusted: duplicates and items not offered at this
     * level are dropped, and required items that are missing are added back. An empty or missing
     * custom list falls back to the defaults.
     */
    fun itemsFor(level: ExplanationLevel, settings: DashboardSettings): List<DashboardItem> {
        val choice = settings.forLevel(level)
        if (choice.layout == HomeLayout.EASY) return defaults(level)
        val custom = choice.customItems.orEmpty().distinct().filter { it.availableAt(level) }
        if (custom.isEmpty()) return defaults(level)
        return normalize(custom)
    }

    /** Adds any missing required items. Car Health goes first; Extras goes last. */
    fun normalize(items: List<DashboardItem>): List<DashboardItem> {
        var out = items.distinct()
        if (DashboardItem.CAR_HEALTH !in out) out = listOf(DashboardItem.CAR_HEALTH) + out
        if (DashboardItem.EXTRAS !in out) out = out + DashboardItem.EXTRAS
        return out
    }

    // ---- Editing (used by the Settings screen) -------------------------------------------------

    /** Switches [level]'s layout. Switching to Customizable starts from what's currently shown. */
    fun setLayout(settings: DashboardSettings, level: ExplanationLevel, layout: HomeLayout): DashboardSettings {
        val current = settings.forLevel(level)
        val items = current.customItems ?: itemsFor(level, settings)
        return settings.with(level, current.copy(layout = layout, customItems = items))
    }

    /** Shows or hides [item] for [level]. Required items can't be hidden. */
    fun setShown(settings: DashboardSettings, level: ExplanationLevel, item: DashboardItem, shown: Boolean): DashboardSettings {
        if (!item.availableAt(level) || (item.required && !shown)) return settings
        val items = editable(settings, level)
        val next = when {
            shown && item !in items -> items.toMutableList().apply {
                // New items go before Extras, which stays last.
                add(indexOf(DashboardItem.EXTRAS).takeIf { it >= 0 } ?: size, item)
            }
            !shown -> items - item
            else -> items
        }
        return settings.with(level, LevelLayout(HomeLayout.CUSTOMIZABLE, next))
    }

    /** Moves [item] one place earlier (`by = -1`) or later (`by = 1`) for [level]. */
    fun move(settings: DashboardSettings, level: ExplanationLevel, item: DashboardItem, by: Int): DashboardSettings {
        val items = editable(settings, level).toMutableList()
        val from = items.indexOf(item)
        val to = from + by
        if (from < 0 || to !in items.indices) return settings
        items.removeAt(from)
        items.add(to, item)
        return settings.with(level, LevelLayout(HomeLayout.CUSTOMIZABLE, items))
    }

    /** Restores the recommended arrangement for [level] only. Other levels keep their layouts. */
    fun restoreDefaults(settings: DashboardSettings, level: ExplanationLevel): DashboardSettings =
        settings.with(level, LevelLayout())

    private fun editable(settings: DashboardSettings, level: ExplanationLevel) = itemsFor(level, settings)
}

/** An optional tool listed in Extras. [item] is set when the tool can also be added to the home screen. */
enum class ExtraTool(val title: String, val description: String, val available: Boolean, val item: DashboardItem? = null) {
    MILEAGE_LOG("Mileage log", "Record odometer readings for your vehicle.", available = true, item = DashboardItem.MILEAGE_LOG),
    SERVICE_RECORDS("Service records", "Keep track of repairs and maintenance.", available = true),
    SAMPLE_DATA("Sample data", "Try the app with labeled example readings. Settings → Data.", available = true),
    FUEL_LOG("Fuel log", "Track fill-ups and fuel economy.", available = false),
    WORK_MILEAGE("Work mileage and tax records", "Separate business trips for your records.", available = false),
    EMISSIONS_READINESS("Emissions readiness", "Check whether your car's emissions monitors are ready.", available = false),
    DRIVE_CYCLE("Drive cycle assistant", "Guidance for completing emissions monitors.", available = false),
}
