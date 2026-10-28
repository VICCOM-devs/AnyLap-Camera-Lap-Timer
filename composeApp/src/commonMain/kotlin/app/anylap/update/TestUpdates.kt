package app.anylap.update

import app.anylap.BuildInfo
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test builds before the store: every CI build of the test branch is published as the GitHub
 * pre-release "test-latest" (APK named anylap-test-<build>.apk). The app checks it at start and offers
 * the newer one; the fixed test signing key lets it install on top, keeping all data.
 */
object TestUpdates {
    class Update(val build: Int, val apkUrl: String, val notes: String)

    private const val API = "https://api.github.com/repos/VICCOM-devs/AnyLap-Camera-Lap-Timer/releases/tags/test-latest"

    /** The newer test build, or null (none, no network, or this build is local/newer). */
    suspend fun check(): Update? {
        if (BuildInfo.BUILD == 0) return null
        return try {
            val client = HttpClient()
            val text = try {
                client.get(API) { header("Accept", "application/vnd.github+json") }.bodyAsText()
            } finally { client.close() }
            val o = Json.parseToJsonElement(text).jsonObject
            val asset = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true } ?: return null
            val name = asset["name"]!!.jsonPrimitive.content
            val build = Regex("(\\d+)\\.apk$").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (build <= BuildInfo.BUILD) return null
            Update(build, asset["browser_download_url"]!!.jsonPrimitive.content, o["body"]?.jsonPrimitive?.content?.lines()?.firstOrNull().orEmpty())
        } catch (t: Throwable) { null }
    }
}
