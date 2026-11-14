package com.aicarcompanion.app.ui.vehicles

import com.aicarcompanion.app.data.vehicledb.CatalogVehicleSpecs
import com.aicarcompanion.app.data.vehicledb.LookupResult
import com.aicarcompanion.app.data.vehicledb.VehicleCatalogProvider
import com.aicarcompanion.app.data.vehicledb.VehicleConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Loading state of one selector's option list. */
sealed interface Options<out T> {
    data object Idle : Options<Nothing>
    data object Loading : Options<Nothing>
    data class Loaded<T>(val items: List<T>, val fromCache: Boolean = false) : Options<T>
    data class Error(val message: String) : Options<Nothing>

    val itemsOrEmpty: List<T> get() = (this as? Loaded<T>)?.items.orEmpty()
}

data class VehicleSelection(
    val years: Options<Int> = Options.Idle,
    val year: Int? = null,
    val makes: Options<String> = Options.Idle,
    val make: String? = null,
    val models: Options<String> = Options.Idle,
    val model: String? = null,
    val configurations: Options<VehicleConfiguration> = Options.Idle,
    val engine: String? = null,
    val configuration: VehicleConfiguration? = null,
    val specs: Options<CatalogVehicleSpecs> = Options.Idle,
) {
    /** Distinct engines for the chosen model, e.g. ["2.0L 4-cyl Diesel Turbo", "1.8L 4-cyl Turbo"]. */
    val engines: List<String> get() = configurations.itemsOrEmpty.map { it.engineLabel }.distinct()

    /** Transmissions available with the chosen engine. */
    val transmissions: List<VehicleConfiguration>
        get() = configurations.itemsOrEmpty.filter { it.engineLabel == engine }

    /** True once a full configuration has been chosen (specs may still be loading). */
    val isComplete: Boolean get() = configuration != null
}

/**
 * Dependent year → make → model → engine → transmission selection. Changing a choice clears
 * everything below it and loads the next list. Single-option levels are chosen automatically.
 */
class VehicleSelectionController(
    private val provider: VehicleCatalogProvider,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(VehicleSelection())
    val state: StateFlow<VehicleSelection> = _state.asStateFlow()

    private var job: Job? = null

    val sourceName: String get() = provider.sourceName

    fun loadYears() {
        if (_state.value.years is Options.Loaded) return
        _state.update { it.copy(years = Options.Loading) }
        launchLoad { _state.update { it.copy(years = provider.years().toOptions()) } }
    }

    fun selectYear(year: Int) {
        _state.update {
            VehicleSelection(years = it.years, year = year, makes = Options.Loading)
        }
        launchLoad { _state.update { it.copy(makes = provider.makes(year).toOptions()) } }
    }

    fun selectMake(make: String) {
        val year = _state.value.year ?: return
        _state.update {
            it.copy(make = make, models = Options.Loading, model = null, configurations = Options.Idle,
                engine = null, configuration = null, specs = Options.Idle)
        }
        launchLoad { _state.update { it.copy(models = provider.models(year, make).toOptions()) } }
    }

    fun selectModel(model: String) {
        val s = _state.value
        val year = s.year ?: return
        val make = s.make ?: return
        _state.update {
            it.copy(model = model, configurations = Options.Loading, engine = null,
                configuration = null, specs = Options.Idle)
        }
        launchLoad {
            val configs = provider.configurations(year, make, model).toOptions()
            _state.update { it.copy(configurations = configs) }
            val engines = _state.value.engines
            if (engines.size == 1) selectEngineNow(engines.single())
        }
    }

    fun selectEngine(engineLabel: String) {
        launchLoad { selectEngineNow(engineLabel) }
    }

    fun selectConfiguration(configuration: VehicleConfiguration) {
        launchLoad { selectConfigurationNow(configuration) }
    }

    fun retry() {
        val s = _state.value
        when {
            s.years is Options.Error -> { _state.update { it.copy(years = Options.Idle) }; loadYears() }
            s.makes is Options.Error && s.year != null -> selectYear(s.year)
            s.models is Options.Error && s.make != null -> selectMake(s.make)
            s.configurations is Options.Error && s.model != null -> selectModel(s.model)
            s.specs is Options.Error && s.configuration != null -> selectConfiguration(s.configuration)
        }
    }

    private suspend fun selectEngineNow(engineLabel: String) {
        _state.update { it.copy(engine = engineLabel, configuration = null, specs = Options.Idle) }
        val transmissions = _state.value.transmissions
        if (transmissions.size == 1) selectConfigurationNow(transmissions.single())
    }

    private suspend fun selectConfigurationNow(configuration: VehicleConfiguration) {
        _state.update { it.copy(configuration = configuration, specs = Options.Loading) }
        val specs = provider.specs(configuration.id).toOptions()
        _state.update { if (it.configuration == configuration) it.copy(specs = specs) else it }
    }

    private fun launchLoad(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { block() }
    }

    private fun <T> LookupResult<List<T>>.toOptions(): Options<T> = when (this) {
        is LookupResult.Success -> Options.Loaded(value, fromCache)
        is LookupResult.Failure -> Options.Error(error.userMessage)
    }

    @JvmName("toSpecOptions")
    private fun LookupResult<CatalogVehicleSpecs>.toOptions(): Options<CatalogVehicleSpecs> = when (this) {
        is LookupResult.Success -> Options.Loaded(listOf(value), fromCache)
        is LookupResult.Failure -> Options.Error(error.userMessage)
    }
}
