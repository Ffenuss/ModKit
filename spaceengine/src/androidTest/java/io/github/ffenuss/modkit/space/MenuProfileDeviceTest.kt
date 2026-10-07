package io.github.ffenuss.modkit.space

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MenuProfileDeviceTest {
    @Test fun spacePullsTwoPreparedMenusAndRejectsChangedBytes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = android.net.Uri.parse("content://io.github.ffenuss.modkit.test.space-menu")
        val input = File(context.cacheDir, "sync-fixture").apply { writeText("original fixture") }
        val first = profile("io.fixture.syncfirst", input).toString()
        val second = profile("io.fixture.syncsecond", input).toString()
        val bundle = android.os.Bundle().apply { putString("first.json", first); putString("second.json", second) }
        try {
            context.getSharedPreferences("modkit_menu_sync", 0).edit().clear().commit()
            context.contentResolver.call(uri, "fixture", null, bundle)
            assertEquals(2, MenuProfileStore.sync(context).size)
            assertEquals("io.fixture.syncfirst", MenuProfileStore.load(context, "io.fixture.syncfirst")!!.packageName)
            assertEquals("io.fixture.syncsecond", MenuProfileStore.load(context, "io.fixture.syncsecond")!!.packageName)
            assertTrue("Unchanged profiles must not be rewritten", MenuProfileStore.sync(context).isEmpty())
            bundle.putString("first.json", JSONObject(first).put("label", "changed").toString())
            bundle.putBoolean("corrupt", true)
            context.contentResolver.call(uri, "fixture", null, bundle)
            assertTrue(MenuProfileStore.sync(context).isEmpty())
            assertEquals("io.fixture.syncfirst", MenuProfileStore.load(context, "io.fixture.syncfirst")!!.label)
            bundle.putBoolean("corrupt", false)
            context.contentResolver.call(uri, "fixture", null, bundle)
            assertEquals(1, MenuProfileStore.sync(context).size)
            assertEquals("changed", MenuProfileStore.load(context, "io.fixture.syncfirst")!!.label)
        } finally {
            context.contentResolver.call(uri, "fixture", null, android.os.Bundle())
            input.delete()
            listOf("io.fixture.syncfirst", "io.fixture.syncsecond").forEach {
                File(File(context.filesDir, "modkit-menus"), "$it.json").delete()
            }
            context.getSharedPreferences("modkit_menu_sync", 0).edit().clear().commit()
        }
    }
    private fun profile(pkg: String, bytes: File): JSONObject = JSONObject()
        .put("schema", 1).put("backend", "none").put("packageName", pkg).put("label", pkg)
        .put("artifactSha256", "a".repeat(64)).put("genre", "Не определён").put("truncated", false)
        .put("engines", JSONArray(listOf("Android / DEX")))
        .put("sources", JSONArray().put(JSONObject().put("sha256", SourceInventory.hash(bytes, SourceInventory.Cancellation())).put("size", bytes.length())))
        .put("items", JSONArray().put(JSONObject().put("id", "fixture").put("category", "Здоровье")
            .put("title", "GetHealth").put("evidence", "Player.GetHealth").put("state", "candidate").put("detail", "Fixture evidence")))
    @Test fun menusStayIndependentAcrossGamesAndByteChangesRejectOldMenu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "profile-fixture").apply { mkdirs() }
        val storage = File(context.filesDir, "modkit-menus").apply { mkdirs() }
        val first = File(root, "copied.apk").apply { writeText("first original") }
        val second = File(root, "other.apk").apply { writeText("second original") }
        try {
            File(storage, "io.fixture.first.json").writeText(profile("io.fixture.first", first).toString())
            File(storage, "io.fixture.second.json").writeText(profile("io.fixture.second", second).toString())
            val firstProfile = MenuProfileStore.load(context, "io.fixture.first")!!
            val secondProfile = MenuProfileStore.load(context, "io.fixture.second")!!
            assertTrue(firstProfile.matches(listOf(first), SourceInventory.Cancellation()))
            assertFalse(firstProfile.matches(listOf(second), SourceInventory.Cancellation()))
            assertTrue(secondProfile.matches(listOf(second), SourceInventory.Cancellation()))
            first.writeText("changed original")
            assertFalse(firstProfile.matches(listOf(first), SourceInventory.Cancellation()))
            assertTrue(secondProfile.matches(listOf(second), SourceInventory.Cancellation()))
            assertEquals("io.fixture.first", MenuProfileStore.load(context, "io.fixture.first")!!.packageName)
        } finally { root.deleteRecursively(); File(storage, "io.fixture.first.json").delete(); File(storage, "io.fixture.second.json").delete() }
    }
    @Test fun incompatibleContractCannotClaimExecutableBackend() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "menu-contract-input").apply { writeText("fixture") }
        try {
            val json = profile("io.fixture.valid", file)
            assertEquals(1, MenuProfile(json.toString()).items.size)
            assertThrows(Exception::class.java) { MenuProfile(json.put("backend", "native_patch").toString()) }
            assertThrows(Exception::class.java) { MenuProfile(json.put("backend", "none").put("schema", 99).toString()) }
            assertThrows(Exception::class.java) { MenuProfile(json.put("schema", 1).put("packageName", "../../escape").toString()) }
        } finally { file.delete() }
    }
}
