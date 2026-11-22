package com.blurr.voice.utilities

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PandaStateManagerTest {

    private lateinit var context: Context
    private lateinit var stateManager: PandaStateManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = PandaStateManager.getInstance(context)
        stateManager.setState(PandaState.IDLE)
    }

    @Test
    fun testPandaStateEnum() {
        val states = PandaState.values()

        assertTrue("IDLE state should exist", states.contains(PandaState.IDLE))
        assertTrue("LISTENING state should exist", states.contains(PandaState.LISTENING))
        assertTrue("PROCESSING state should exist", states.contains(PandaState.PROCESSING))
        assertTrue("SPEAKING state should exist", states.contains(PandaState.SPEAKING))
        assertTrue("ERROR state should exist", states.contains(PandaState.ERROR))
        assertEquals("Should have exactly 5 states", 5, states.size)
    }

    @Test
    fun testStateColorsAndPriority() {
        val idleColor = DeltaStateColorMapper.getColor(context, PandaState.IDLE)
        val listeningColor = DeltaStateColorMapper.getColor(context, PandaState.LISTENING)
        val processingColor = DeltaStateColorMapper.getColor(context, PandaState.PROCESSING)
        val speakingColor = DeltaStateColorMapper.getColor(context, PandaState.SPEAKING)
        val errorColor = DeltaStateColorMapper.getColor(context, PandaState.ERROR)

        assertNotEquals("IDLE and LISTENING colors should differ", idleColor, listeningColor)
        assertNotEquals("PROCESSING and SPEAKING colors should differ", processingColor, speakingColor)
        assertNotEquals("SPEAKING and ERROR colors should differ", speakingColor, errorColor)

        assertFalse(DeltaStateColorMapper.isActiveState(PandaState.IDLE))
        assertTrue(DeltaStateColorMapper.isActiveState(PandaState.LISTENING))
        assertTrue(DeltaStateColorMapper.isActiveState(PandaState.PROCESSING))
        assertTrue(DeltaStateColorMapper.isActiveState(PandaState.SPEAKING))
        assertTrue(DeltaStateColorMapper.isErrorState(PandaState.ERROR))
    }

    @Test
    fun testStateManagerNotifiesListeners() {
        val observed = mutableListOf<PandaState>()
        val listener: (PandaState) -> Unit = { observed.add(it) }
        stateManager.addStateChangeListener(listener)

        try {
            shadowOf(Looper.getMainLooper()).idle()
            observed.clear()

            stateManager.setState(PandaState.LISTENING)
            stateManager.setState(PandaState.PROCESSING)
            stateManager.setState(PandaState.SPEAKING)
            stateManager.setState(PandaState.IDLE)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(
                listOf(
                    PandaState.LISTENING,
                    PandaState.PROCESSING,
                    PandaState.SPEAKING,
                    PandaState.IDLE
                ),
                observed
            )
            assertEquals(PandaState.IDLE, stateManager.getCurrentState())
        } finally {
            stateManager.removeStateChangeListener(listener)
        }
    }
}