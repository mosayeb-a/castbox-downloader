package com.castboxdownloader.service

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.net.URL

class CastboxService {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    suspend fun extractPodcastInfo(url: String): PodcastInfo {
        if (!isValidCastboxUrl(url)) {
            throw IllegalArgumentException("Invalid Castbox URL")
        }

        val episodeId = extractEpisodeId(url)
        println("📦 Found episode ID: $episodeId")

        val audioUrl = fetchAudioUrl(episodeId)
        val (title, description) = fetchMetadata(url)

        if (!isValidAudioUrl(audioUrl)) {
            throw IllegalArgumentException("Invalid audio URL format")
        }

        return PodcastInfo(title, description, audioUrl)
    }

    private fun isValidCastboxUrl(url: String): Boolean = try {
        val parsedUrl = URL(url)
        val host = parsedUrl.host
        val path = parsedUrl.path
        host.contains("castbox.fm") && (path.startsWith("/episode/") || path.startsWith("/vb/"))
    } catch (e: Exception) {
        false
    }

    private fun extractEpisodeId(url: String): String {
        val doc = fetchDocument(url)
        
        return when {
            url.contains("/vb/") -> {
                Regex("\\d+").find(url.substringAfter("/vb/"))?.value
                    ?: throw Exception("Could not find episode ID in /vb/ URL")
            }
            url.contains("-id") -> {
                Regex("-id(\\d+)(?:$|\\?|-)").findAll(url).lastOrNull()?.groupValues?.get(1)
                    ?: throw Exception("Could not find episode ID in URL")
            }
            else -> {
                findEpisodeIdInScripts(doc)
                    ?: throw Exception("Could not find episode ID in page content")
            }
        }
    }

    private fun findEpisodeIdInScripts(doc: org.jsoup.nodes.Document): String? {
        val scriptTags = doc.select("script[type='text/javascript']")
        
        for (script in scriptTags) {
            val scriptData = script.data()
            when {
                scriptData.contains("\"eid\":") ->
                    return scriptData.substringAfter("\"eid\":\"").substringBefore("\"")
                scriptData.contains("\"episodeId\":") ->
                    return scriptData.substringAfter("\"episodeId\":\"").substringBefore("\"")
                scriptData.contains("episode_id") -> {
                    val match = Regex("episode_id['\"]?\\s*:\\s*['\"]?(\\d+)").find(scriptData)
                    if (match != null) return match.groupValues[1]
                }
            }
        }
        return null
    }

    private suspend fun fetchAudioUrl(episodeId: String): String {
        return try {
            println("🌐 Trying primary API...")
            fetchFromPrimaryApi(episodeId)
        } catch (e: Exception) {
            println("⚠️ Primary API failed, trying alternative methods...")
            try {
                fetchFromSecondaryApi(episodeId)
            } catch (e: Exception) {
                println("⚠️ Alternative API failed, falling back to meta tags...")
                throw e
            }
        }
    }

    private suspend fun fetchFromPrimaryApi(episodeId: String): String {
        val apiUrl = "https://everest.castbox.fm/web/v1/episode?eid=$episodeId"
        val response = client.get(apiUrl) {
            header("Accept", "application/json")
            header("Origin", "https://castbox.fm")
            header("Referer", "https://castbox.fm/")
        }
        
        val responseBody = response.body<JsonObject>()
        return responseBody["data"]?.jsonObject?.get("audio")?.jsonPrimitive?.content
            ?: throw Exception("Could not find audio URL in primary API response")
    }

    private suspend fun fetchFromSecondaryApi(episodeId: String): String {
        val apiUrl = "https://api.castbox.fm/v2/episodes/$episodeId"
        val response = client.get(apiUrl) {
            header("Accept", "application/json")
            header("Origin", "https://castbox.fm")
            header("Referer", "https://castbox.fm/")
        }
        
        val responseBody = response.body<JsonObject>()
        return responseBody["audio"]?.jsonPrimitive?.content
            ?: throw Exception("Could not find audio URL in secondary API response")
    }

    private fun fetchDocument(url: String): org.jsoup.nodes.Document {
        return Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .followRedirects(true)
            .get()
    }

    private fun fetchMetadata(url: String): Pair<String, String> {
        val doc = fetchDocument(url)
        
        val title = doc.select("meta[property=og:title]").firstOrNull()?.attr("content")
            ?: doc.select("h1").firstOrNull()?.text()
            ?: doc.select("title").firstOrNull()?.text()
            ?: throw Exception("Could not find title")

        val description = cleanDescription(
            doc.select("meta[property=og:description]").firstOrNull()?.attr("content")
                ?: doc.select("meta[name=description]").firstOrNull()?.attr("content")
                ?: doc.select("div.episode-description").firstOrNull()?.text()
                ?: "No description available"
        )

        return title to description
    }

    private fun cleanDescription(description: String): String =
        Jsoup.parse(description).text()

    private fun isValidAudioUrl(url: String): Boolean = try {
        val parsedUrl = URL(url)
        val path = parsedUrl.path.lowercase()
        path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac") ||
            url.contains("audio") || url.contains("media") || url.contains("stream")
    } catch (e: Exception) {
        false
    }

    @Serializable
    data class PodcastInfo(
        val title: String,
        val description: String,
        val audioUrl: String
    )
}