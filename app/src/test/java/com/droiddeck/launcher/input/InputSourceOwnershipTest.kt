package com.droiddeck.launcher.input

import java.io.File
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [JoyConPadTest.RecordingWriter::class])
class InputSourceOwnershipTest {
    private val bridge = PadBridge(File(RuntimeEnvironment.getApplication().cacheDir, "input-ownership"))
    private fun state() = JoyConPadTest.RecordingWriter.pads[0]!!
    @After fun close() { bridge.stop(); DeckControls.releaseAll(); DeckControls.setRouter(null) }

    @Test fun closingOneSurfaceDoesNotReleaseAnotherSurfaceOrPhysicalPad() {
        val a = Any(); val b = Any()
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        bridge.applyTouch(a, InputRole.PRIMARY) { it.press(PadState.A, true); it.press(PadState.B, true) }
        bridge.applyTouch(b, InputRole.SECONDARY) { it.press(PadState.X, true) }
        bridge.releaseTouch(a)
        assertTrue(state().isDown(PadState.A)); assertTrue(state().isDown(PadState.X)); assertFalse(state().isDown(PadState.B))
        bridge.releaseTouch(b)
        assertTrue(state().isDown(PadState.A)); assertFalse(state().isDown(PadState.X))
    }

    @Test fun disconnectingSecondaryDeckSurfaceKeepsPhysicalGripHeld() {
        val surface = DeckControls.source(InputRole.SECONDARY)
        DeckControls.setGrip(DeckControls.L4, true)
        surface.setGrip(DeckControls.L4, true)
        surface.close()
        assertEquals(DeckControls.L4, JoyConPadTest.RecordingWriter.grips)
        DeckControls.setGrip(DeckControls.L4, false)
        assertEquals(0, JoyConPadTest.RecordingWriter.grips)
    }

    @Test fun nativePadAndTouchMappingsUseDifferentRoles() {
        val output = object : InputOutput {
            override fun key(code: Int, down: Boolean) {}
            override fun button(code: Int, down: Boolean) {}
            override fun pointer(dx: Float, dy: Float) {}
            override fun scroll(steps: Int) {}
            override fun command(command: InputCommand) {}
            override fun requestMotion() {}
        }
        val router = InputRouter(output)
        bridge.setInputRouter(router)
        bridge.setInputProfile(InputProfile.defaults(InputProfile.GAME).withBinding(InputRole.SECONDARY, InputControl.A, InputAction.Disabled))
        val touch = Any()
        bridge.applyTouch(touch, InputRole.SECONDARY) { it.press(PadState.A, true) }
        assertFalse(state().isDown(PadState.A))
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        assertTrue(state().isDown(PadState.A))
        bridge.releaseAll()
        assertFalse(state().isDown(PadState.A))
        bridge.applyExternal({ it.press(PadState.B, true) }, true)
        assertFalse(state().isDown(PadState.A))
        assertTrue(state().isDown(PadState.B))
        bridge.applyExternal({ it.press(PadState.A, false) }, true)
        bridge.applyExternal({ it.press(PadState.A, true) }, true)
        assertTrue(state().isDown(PadState.A))
    }
}
