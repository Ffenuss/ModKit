package io.github.ffenuss.modkit.space

import android.app.Application
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dualspace.multispace.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference

/** Checks the actual host UI code without claiming virtual-kernel startup. */
@RunWith(AndroidJUnit4::class)
class HostPanelDeviceTest {
    private fun field(name: String, value: Any?) = SpaceHost::class.java.getDeclaredField(name).apply {
        isAccessible = true
        set(null, value)
    }
    private fun call(name: String) = SpaceHost::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(null)
    private fun count(view: View, tag: String): Int = (if (view.tag == tag) 1 else 0) +
        (if (view is ViewGroup) (0 until view.childCount).sumOf { count(view.getChildAt(it), tag) } else 0)

    @Test fun oneActivityPanelOpensAndClosesWithoutSystemOverlayPermission() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        assertFalse("The fixture must not hold a system overlay grant", Settings.canDrawOverlays(app))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { activity ->
                    field("application", app)
                    field("activity", WeakReference(activity))
                    field("target", "dev.modkit.nativefixture")
                    field("targets", SpaceTargetStore(app.getSharedPreferences("panel-test-targets", 0)))
                    val attach = SpaceHost::class.java.getDeclaredMethod("attachHostButton", android.app.Activity::class.java).apply { isAccessible = true }
                    attach.invoke(null, activity); attach.invoke(null, activity)
                    val decor = activity.window.decorView
                    assertEquals(1, count(decor, "modkit-space-entry"))
                    val entry = decor.findViewWithTag<View>("modkit-space-entry")
                    assertTrue(entry.performClick())
                    assertEquals(1, count(decor, "modkit-space-panel"))
                    assertFalse(Settings.canDrawOverlays(app))
                    assertTrue(entry.performClick())
                    assertEquals(0, count(decor, "modkit-space-panel"))
                    assertTrue(entry.performClick())
                    assertEquals(1, count(decor, "modkit-space-panel"))
                    call("removeOverlay")
                    assertEquals(0, count(decor, "modkit-space-panel"))
                }
            } finally {
                instrumentation.runOnMainSync {
                    call("removeOverlay")
                    field("activity", WeakReference<android.app.Activity>(null))
                    field("application", null)
                    field("targets", null)
                    field("target", null)
                }
            }
        }
    }
}
