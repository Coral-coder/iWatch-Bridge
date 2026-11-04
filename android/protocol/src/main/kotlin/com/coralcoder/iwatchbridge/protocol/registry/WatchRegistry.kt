package com.coralcoder.iwatchbridge.protocol.registry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Every watch paired to this phone, like the iPhone's "All Watches" list. Each watch has its own
 * pairing record: its own link keys, IKE long-term keys and IDS identity, so pairing a second
 * watch never disturbs the first.
 *
 * As on the iPhone, one watch is *active* at a time (it gets notifications, calls and settings
 * changes). With Auto Switch on, the active watch follows whichever paired watch you put on and
 * unlock; the others stay paired and sync when they become active again.
 */
@Serializable
data class WatchRecord(
    val id: String,                     // our own UUID for the pairing
    val name: String,                   // "Dana's Apple Watch"
    val model: String = "",             // marketing name, e.g. "Apple Watch Ultra 2"
    val productType: String = "",       // e.g. "Watch7,5"
    val osVersion: String = "",         // e.g. "26.2"
    val serial: String = "",
    val caseSizeMm: Int = 0,
    val btAddress: String = "",         // BR/EDR public address, MSB first
    val leIdentityAddress: String = "",
    val pairedAtEpochMs: Long = 0,
    val lastConnectedEpochMs: Long = 0,
    /** Opaque, encrypted-at-rest secrets blob id (the app's keystore holds the keys). */
    val secretsRef: String = "",
    val activationState: ActivationState = ActivationState.UNKNOWN,
    val familyMember: String? = null,   // Family Setup watches are managed, not "worn by" this phone's owner
)

@Serializable
enum class ActivationState { UNKNOWN, NEEDS_ACTIVATION, ACTIVATION_LOCKED, ACTIVATED }

@Serializable
data class RegistryState(
    val watches: List<WatchRecord> = emptyList(),
    val activeId: String? = null,
    val autoSwitch: Boolean = true,
) {
    val active get() = watches.firstOrNull { it.id == activeId }
}

/** Where the registry is persisted (the app stores it in its private files dir). */
interface RegistryStore {
    fun load(): String?
    fun save(json: String)
}

class WatchRegistry(private val store: RegistryStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _state = MutableStateFlow(store.load()?.let { runCatching { json.decodeFromString<RegistryState>(it) }.getOrNull() } ?: RegistryState())
    val state: StateFlow<RegistryState> = _state.asStateFlow()

    private fun update(f: (RegistryState) -> RegistryState) = synchronized(this) {
        val next = f(_state.value)
        _state.value = next
        store.save(json.encodeToString(next))
        next
    }

    /** Add or replace a pairing. The first watch becomes active; later ones don't steal it. */
    fun upsert(record: WatchRecord) = update { s ->
        val others = s.watches.filterNot { it.id == record.id }
        s.copy(watches = others + record, activeId = s.activeId ?: record.id)
    }

    fun remove(id: String) = update { s ->
        val rest = s.watches.filterNot { it.id == id }
        s.copy(watches = rest, activeId = if (s.activeId == id) rest.maxByOrNull { it.lastConnectedEpochMs }?.id else s.activeId)
    }

    fun setActive(id: String) = update { s ->
        require(s.watches.any { it.id == id }) { "unknown watch $id" }
        s.copy(activeId = id)
    }

    fun setAutoSwitch(on: Boolean) = update { it.copy(autoSwitch = on) }

    fun markConnected(id: String, now: Long = System.currentTimeMillis()) = update { s ->
        s.copy(watches = s.watches.map { if (it.id == id) it.copy(lastConnectedEpochMs = now) else it })
    }

    /**
     * What the watches report about themselves, used for Auto Switch. iPhone switches to the watch
     * that was most recently put on the wrist and unlocked; a watch that's off-wrist or locked
     * never takes over.
     */
    data class WristSignal(val watchId: String, val onWrist: Boolean, val unlocked: Boolean, val sinceEpochMs: Long)

    /** Applies Auto Switch; returns the new active id if it changed. */
    fun autoSwitch(signals: List<WristSignal>): String? {
        val s = _state.value
        if (!s.autoSwitch) return null
        val candidate = signals
            .filter { it.onWrist && it.unlocked && s.watches.any { w -> w.id == it.watchId } && s.watches.first { w -> w.id == it.watchId }.familyMember == null }
            .maxByOrNull { it.sinceEpochMs } ?: return null
        if (candidate.watchId == s.activeId) return null
        setActive(candidate.watchId)
        return candidate.watchId
    }
}
