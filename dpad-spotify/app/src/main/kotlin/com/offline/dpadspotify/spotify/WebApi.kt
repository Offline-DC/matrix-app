package com.offline.dpadspotify.spotify

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class TrackResult(
    val uri: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationMs: Int,
)

/**
 * Minimal Spotify Web API client. Only `/v1/search` for now — playback itself
 * goes through librespot, the Web API is just the easiest way to turn a T9
 * query into track URIs. Auth is a bearer token minted by the librespot
 * session (Login5), so there's no separate app registration or OAuth dance.
 */
object WebApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @Throws(IOException::class)
    fun searchTracks(accessToken: String, query: String, limit: Int = 20): List<TrackResult> {
        val url = "https://api.spotify.com/v1/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("type", "track")
            .addQueryParameter("limit", limit.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Search failed: HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("Empty search response")
            return parseTracks(JsonParser.parseString(body).asJsonObject)
        }
    }

    private fun parseTracks(root: JsonObject): List<TrackResult> {
        val items = root.getAsJsonObject("tracks")?.getAsJsonArray("items") ?: return emptyList()
        return items.mapNotNull { el ->
            val t = el.asJsonObject
            val uri = t.get("uri")?.asString ?: return@mapNotNull null
            TrackResult(
                uri = uri,
                name = t.get("name")?.asString ?: "Unknown",
                artist = t.getAsJsonArray("artists")
                    ?.joinToString(", ") { it.asJsonObject.get("name").asString }
                    ?: "",
                album = t.getAsJsonObject("album")?.get("name")?.asString ?: "",
                durationMs = t.get("duration_ms")?.asInt ?: 0,
            )
        }
    }
}
