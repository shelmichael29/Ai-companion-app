package com.aicarcompanion.app.diagnostics.obd

/**
 * Safety gate for every command sent to an adapter. Only adapter configuration and standard
 * read-only OBD-II requests are allowed. Everything else is rejected before any bytes are sent,
 * including:
 * - mode 04 (clear trouble codes / reset monitors) and mode 08 (on-board system control),
 * - UDS services such as 0x11 ECU reset, 0x14 clear diagnostic info, 0x2E write data,
 *   0x2F input/output control (actuator tests), 0x31 routine control, 0x27 security access,
 * - AT commands that set custom headers or send arbitrary frames (ATSH, ATCRA, ATMA, …).
 */
object ReadOnlyCommandPolicy {

    private val ALLOWED = listOf(
        // Adapter setup (affects only the adapter, never the vehicle).
        Regex("^ATZ$"), Regex("^ATWS$"), Regex("^ATD$"), Regex("^ATI$"), Regex("^AT@1$"),
        Regex("^ATE[01]$"), Regex("^ATL[01]$"), Regex("^ATS[01]$"), Regex("^ATH[01]$"),
        Regex("^ATAT[012]$"), Regex("^ATSP[0-9A-C]$"), Regex("^ATTP[0-9A-C]$"), Regex("^ATST[0-9A-F]{2}$"),
        Regex("^ATDPN?$"), Regex("^ATRV$"), Regex("^ATCAF1$"),
        // OBD-II read-only services.
        Regex("^01[0-9A-F]{2}[1-9]?$"), // current data
        Regex("^02[0-9A-F]{4}$"), // freeze frame
        Regex("^03$"), // stored DTCs
        Regex("^06[0-9A-F]{2}$"), // on-board monitoring test results
        Regex("^07$"), // pending DTCs
        Regex("^09[0-9A-F]{2}$"), // vehicle information (VIN, calibration IDs)
        Regex("^0A$"), // permanent DTCs
    )

    fun normalize(command: String): String = command.uppercase().filterNot { it.isWhitespace() }

    fun isAllowed(command: String): Boolean {
        val c = normalize(command)
        return ALLOWED.any { it.matches(c) }
    }

    /** @throws BlockedCommandException if [command] is not on the read-only allowlist. */
    fun requireAllowed(command: String) {
        if (!isAllowed(command)) throw BlockedCommandException(normalize(command))
    }
}

class BlockedCommandException(command: String) :
    IllegalArgumentException("Blocked \"$command\": only read-only OBD-II requests are permitted.")
