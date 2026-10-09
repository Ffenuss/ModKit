package io.github.ffenuss.modkit.space

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.modkit.nativefixture.GameActivity
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ElfImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/** Real original owned app, native library, Activity callbacks and production menu code; no virtual-kernel claim. */
@RunWith(AndroidJUnit4::class)
class GuestMenuDeviceTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun count(view: View, tag: String): Int = (if (tag == view.tag) 1 else 0) +
        (if (view is ViewGroup) (0 until view.childCount).sumOf { count(view.getChildAt(it), tag) } else 0)
    @Test fun actualOverlayTogglesNativeCodeAndSurvivesActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val original = File(app.applicationInfo.sourceDir)
        val before = SourceInventory.hash(original, SourceInventory.Cancellation())
        val abi = SpaceNativePayload.processAbi()
        val image = File(app.cacheDir, "menu-analysis-image.so")
        ZipFile(original).use { zip -> zip.getInputStream(zip.getEntry("lib/$abi/libmodkit_fixture.so")).use { input ->
            image.outputStream().use { input.copyTo(it) }
        } }
        val running = object : CancellationSignal { override fun isCancelled() = false }
        val (address, expected) = ElfImage.open(image, running).use { elf ->
            val symbol = elf.dynamicSymbols.single { it.name == "modkit_fixture_value" && it.defined }
            symbol.value to hex(requireNotNull(elf.readFileWindowAtVa(symbol.value, 8)))
        }
        val replacement = when (abi) {
            "arm64-v8a" -> "e07c8052c0035fd6"; "armeabi-v7a" -> "e70300e31eff2fe1"
            "x86", "x86_64" -> "b8e7030000c39090"; else -> error(abi)
        }
        val json = JSONObject().put("schema", 2).put("backend", "native_v1").put("packageName", app.packageName)
            .put("label", "Owned guest").put("genre", "Fixture").put("artifactSha256", "a".repeat(64)).put("truncated", false)
            .put("engines", JSONArray(listOf("Native fixture")))
            .put("sources", JSONArray().put(JSONObject().put("sha256", before).put("size", original.length())))
            .put("items", JSONArray().put(JSONObject().put("id", "fixture-value").put("category", "Здоровье")
                .put("title", "Здоровье · 999").put("state", "static_recipe").put("detail", "Owned fixture").put("evidence", "modkit_fixture_value")
                .put("patch", JSONObject().put("module", "libmodkit_fixture.so").put("abi", abi).put("address", address)
                    .put("expected", expected).put("replacement", replacement).put("imageSha256", SourceInventory.hash(image, SourceInventory.Cancellation())))))
        val (getterAddress, getterExpected) = ElfImage.open(image, running).use { elf ->
            val symbol = elf.dynamicSymbols.single { it.name == "Java_dev_modkit_nativefixture_GameActivity_getHealth" && it.defined }
            symbol.value to hex(requireNotNull(elf.readFileWindowAtVa(symbol.value, 8)))
        }
        val getterReplacement = when (abi) {
            "arm64-v8a" -> "e0e18452c0035fd6"; "armeabi-v7a" -> "0f0702e31eff2fe1"
            "x86", "x86_64" -> "b80f270000c39090"; else -> error(abi)
        }
        json.getJSONArray("items").put(JSONObject().put("id", "jni-health").put("category", "Здоровье")
            .put("title", "JNI · 9999").put("state", "static_recipe").put("detail", "Typed JNI getter").put("evidence", "getHealth()I")
            .put("patch", JSONObject().put("module", "libmodkit_fixture.so").put("abi", abi).put("address", getterAddress)
                .put("expected", getterExpected).put("replacement", getterReplacement).put("imageSha256", SourceInventory.hash(image, SourceInventory.Cancellation()))))
        val wideReturns = abi == "arm64-v8a" || abi == "x86_64"
        val staminaValue = if (abi == "x86_64") 4_294_977_295L else 9999L
        val speedValue = if (abi == "x86_64") 2.5 else 2.0
        if (wideReturns) {
            fun addWideGetter(id: String, method: String, replacement: String) {
                val length = replacement.length / 2
                val (va, bytes) = ElfImage.open(image, running).use { elf ->
                    val symbol = elf.dynamicSymbols.single {
                        it.name == "Java_dev_modkit_nativefixture_GameActivity_$method" && it.defined
                    }
                    assertEquals("Fixture must expose the complete bounded JNI body", length.toLong(), symbol.size)
                    symbol.value to hex(requireNotNull(elf.readFileWindowAtVa(symbol.value, length)))
                }
                json.getJSONArray("items").put(JSONObject().put("id", id).put("category", "Параметр")
                    .put("title", "JNI · $method").put("state", "static_recipe")
                    .put("detail", "Owned wide return fixture").put("evidence", method)
                    .put("patch", JSONObject().put("module", "libmodkit_fixture.so").put("abi", abi)
                        .put("address", va).put("expected", bytes).put("replacement", replacement)
                        .put("imageSha256", SourceInventory.hash(image, SourceInventory.Cancellation()))))
            }
            addWideGetter("jni-stamina", "getStamina", if (abi == "x86_64")
                "48b80f27000001000000c39090909090" else "e0e184d2c0035fd6")
            addWideGetter("jni-speed", "getMoveSpeed", if (abi == "x86_64")
                "48b8000000000000044066480f6ec0c3" else "0010601ec0035fd6")
        }
        val profile = MenuProfile(json.toString())
        var menu: SpaceGuestMenu? = null
        instrumentation.runOnMainSync {
            menu = SpaceGuestMenu.bind(app, SpaceGuestBootstrap.Session(app.packageName, 0, app, app.classLoader, true), object : SpaceGuestMenu.Inputs {
                override fun profile() = profile
                override fun sources() = listOf(original)
            })
        }
        val device = UiDevice.getInstance(instrumentation)
        try {
            ActivityScenario.launch(GameActivity::class.java).use { scenario ->
                assertTrue(device.wait(Until.hasObject(By.text("Native value: 7")), 15000))
                fun ready(id: String = "fixture-value") {
                    val deadline = System.currentTimeMillis() + 20000
                    var available = false
                    while (!available && System.currentTimeMillis() < deadline) {
                        scenario.onActivity { a -> available = a.window.decorView.findViewWithTag<Switch>("modkit-recipe:$id")?.isEnabled == true }
                        if (!available) device.waitForIdle(200)
                    }
                    assertTrue("Real guest overlay must enable the verified recipe", available)
                }
                ready()
                assertEquals(7, GameActivity.getHealth())
                assertEquals(11L, GameActivity.getStamina())
                assertEquals(1.0, GameActivity.getMoveSpeed(), 0.0)
                scenario.onActivity { a ->
                    assertEquals(1, count(a.window.decorView, "modkit-guest-menu"))
                    a.window.decorView.findViewWithTag<Switch>("modkit-recipe:fixture-value").performClick()
                }
                ready()
                requireNotNull(device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("read native value", java.util.regex.Pattern.CASE_INSENSITIVE))), 10000)).click()
                assertTrue(device.wait(Until.hasObject(By.text("Native value: 999")), 5000))
                scenario.recreate(); ready()
                scenario.onActivity { a ->
                    assertEquals(1, count(a.window.decorView, "modkit-guest-menu"))
                    val toggle = a.window.decorView.findViewWithTag<Switch>("modkit-recipe:fixture-value")
                    assertTrue(toggle.isChecked); toggle.performClick()
                }
                ready(); requireNotNull(device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("read native value", java.util.regex.Pattern.CASE_INSENSITIVE))), 10000)).click()
                assertTrue(device.wait(Until.hasObject(By.text("Native value: 7")), 5000))
                scenario.onActivity { a -> a.window.decorView.findViewWithTag<Switch>("modkit-recipe:jni-health").performClick() }
                ready("jni-health")
                assertEquals("The original typed JNI getter must execute the changed code", 9999, GameActivity.getHealth())
                if (wideReturns) {
                    scenario.onActivity { a -> a.window.decorView.findViewWithTag<Switch>("modkit-recipe:jni-stamina").performClick() }
                    ready("jni-stamina")
                    assertEquals("JNI long must preserve its full return width", staminaValue, GameActivity.getStamina())
                    assertEquals("Long recipe must not affect the double getter", 1.0, GameActivity.getMoveSpeed(), 0.0)
                    scenario.onActivity { a -> a.window.decorView.findViewWithTag<Switch>("modkit-recipe:jni-speed").performClick() }
                    ready("jni-speed")
                    assertEquals("JNI double must use its floating return register", speedValue, GameActivity.getMoveSpeed(), 0.0)
                    assertEquals("Double recipe must not affect the long getter", staminaValue, GameActivity.getStamina())
                    scenario.recreate(); ready("jni-stamina"); ready("jni-speed")
                    assertEquals(staminaValue, GameActivity.getStamina())
                    assertEquals(speedValue, GameActivity.getMoveSpeed(), 0.0)
                    scenario.onActivity { a -> a.window.decorView.findViewWithTag<Switch>("modkit-recipe:jni-stamina").performClick() }
                    ready("jni-stamina")
                    assertEquals(11L, GameActivity.getStamina())
                    assertEquals(speedValue, GameActivity.getMoveSpeed(), 0.0)
                    // Leave both wide recipes enabled to exercise close-time restoration.
                    scenario.onActivity { a -> a.window.decorView.findViewWithTag<Switch>("modkit-recipe:jni-stamina").performClick() }
                    ready("jni-stamina")
                }
                device.findObject(By.desc("Свернуть или открыть меню ModKit")).click()
                scenario.onActivity { a ->
                    val root = a.window.decorView.findViewWithTag<View>("modkit-guest-menu")
                    assertTrue("Collapsed bubble must be smaller than the panel", root.width < a.resources.displayMetrics.density * 160)
                    assertTrue(root.x >= 0 && root.y >= 0)
                }
                val bubble = device.findObject(By.desc("Свернуть или открыть меню ModKit"))
                val from = bubble.visibleCenter
                device.swipe(from.x, from.y, 40, 220, 20)
                scenario.onActivity { a ->
                    val root = a.window.decorView.findViewWithTag<View>("modkit-guest-menu")
                    assertTrue(root.x >= 0 && root.x + root.width <= a.window.decorView.width)
                }
            }
        } finally {
            instrumentation.runOnMainSync { assertTrue("Closing the menu must restore enabled recipes", menu!!.close()) }
            image.delete()
        }
        assertEquals("Closing must restore the original JNI getter", 7, GameActivity.getHealth())
        assertEquals("Closing must restore the original JNI long", 11L, GameActivity.getStamina())
        assertEquals("Closing must restore the original JNI double", 1.0, GameActivity.getMoveSpeed(), 0.0)
        assertEquals("The original guest APK must stay unchanged", before, SourceInventory.hash(original, SourceInventory.Cancellation()))
    }
}
