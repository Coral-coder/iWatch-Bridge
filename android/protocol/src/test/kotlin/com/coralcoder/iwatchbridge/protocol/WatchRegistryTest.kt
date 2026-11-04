package com.coralcoder.iwatchbridge.protocol

import com.coralcoder.iwatchbridge.protocol.registry.RegistryStore
import com.coralcoder.iwatchbridge.protocol.registry.WatchRecord
import com.coralcoder.iwatchbridge.protocol.registry.WatchRegistry
import com.coralcoder.iwatchbridge.protocol.registry.WatchRegistry.WristSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchRegistryTest {
    private class MemStore(var data: String? = null) : RegistryStore {
        override fun load() = data
        override fun save(json: String) { data = json }
    }

    private val ultra = WatchRecord("u", "Ultra", model = "Apple Watch Ultra 2")
    private val s10 = WatchRecord("s", "Series 10", model = "Apple Watch Series 10")
    private val kid = WatchRecord("k", "Sam's Watch", familyMember = "Sam")

    @Test fun firstWatchBecomesActiveAndSecondDoesNotStealIt() {
        val r = WatchRegistry(MemStore())
        r.upsert(ultra); r.upsert(s10)
        assertEquals("u", r.state.value.activeId)
        assertEquals(2, r.state.value.watches.size)
    }

    @Test fun persistsAcrossInstances() {
        val store = MemStore()
        WatchRegistry(store).apply { upsert(ultra); upsert(s10); setActive("s") }
        val again = WatchRegistry(store)
        assertEquals("s", again.state.value.activeId)
        assertEquals(listOf("u", "s"), again.state.value.watches.map { it.id })
    }

    @Test fun autoSwitchPicksMostRecentlyWornUnlockedWatch() {
        val r = WatchRegistry(MemStore()).apply { upsert(ultra); upsert(s10); upsert(kid) }
        assertEquals("s", r.autoSwitch(listOf(
            WristSignal("u", onWrist = true, unlocked = true, sinceEpochMs = 100),
            WristSignal("s", onWrist = true, unlocked = true, sinceEpochMs = 200),
            WristSignal("k", onWrist = true, unlocked = true, sinceEpochMs = 300), // Family Setup: never active
        )))
        assertNull(r.autoSwitch(listOf(WristSignal("u", onWrist = true, unlocked = false, sinceEpochMs = 400))))
        assertEquals("s", r.state.value.activeId)
    }

    @Test fun autoSwitchOffKeepsManualChoice() {
        val r = WatchRegistry(MemStore()).apply { upsert(ultra); upsert(s10); setAutoSwitch(false) }
        assertNull(r.autoSwitch(listOf(WristSignal("s", true, true, 1))))
        assertEquals("u", r.state.value.activeId)
    }

    @Test fun removingActiveWatchFallsBackToMostRecentlyConnected() {
        val r = WatchRegistry(MemStore()).apply {
            upsert(ultra); upsert(s10); upsert(kid)
            markConnected("s", 50); markConnected("k", 10)
            remove("u")
        }
        assertEquals("s", r.state.value.activeId)
    }
}
