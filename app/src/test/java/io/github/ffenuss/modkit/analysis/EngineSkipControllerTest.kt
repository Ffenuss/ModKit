package io.github.ffenuss.modkit.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineSkipControllerTest {
    @Test
    fun skipRequestIsEngineScopedAndConsumable() {
        val controller = EngineSkipController()

        assertFalse(controller.isRequested("a"))
        controller.request("a")
        assertTrue(controller.isRequested("a"))
        assertFalse(controller.isRequested("b"))
        assertTrue(controller.consume("a"))
        assertFalse(controller.isRequested("a"))
        assertFalse(controller.consume("a"))
    }
}
