package com.castboxdownloader_cli

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.net.URL

class CastboxDownloader {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    suspend fun downloadPodcast(url: String) {
        if (!isValidCastboxUrl(url)) {
            println(
                """
                ❌ Invalid Castbox URL
                
                The URL should be in one of these formats:
                1. https://castbox.fm/episode/...
                2. https://castbox.fm/vb/...
            """.trimIndent()
            )
            return
        }

        try {
            println("🔍 Analyzing URL: $url")
            val podcastInfo = extractPodcastInfo(url)

            println(
                """
                🎙️ ${podcastInfo.title}
                
                📝 ${podcastInfo.description}
                
                📥 Download link:
                ${podcastInfo.audioUrl}
            """.trimIndent()
            )

        } catch (e: Exception) {
            println("❌ Error: ${e.message}")
        }
    }

    private fun isValidCastboxUrl(url: String): Boolean {
        return try {
            val parsedUrl = URL(url)
            val host = parsedUrl.host
            val path = parsedUrl.path
            host.contains("castbox.fm") && (path.startsWith("/episode/") || path.startsWith("/vb/"))
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun extractPodcastInfo(url: String): PodcastInfo {
        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .followRedirects(true)
            .get()

        val episodeId = when {
            url.contains("/vb/") -> {
                val match = Regex("\\d+").find(url.substringAfter("/vb/"))
                match?.value ?: throw Exception("Could not find episode ID in /vb/ URL")
            }

            url.contains("-id") -> {
                val match = Regex("-id(\\d+)(?:$|\\?|-)").findAll(url).lastOrNull()
                match?.groupValues?.get(1) ?: throw Exception("Could not find episode ID in URL")
            }

            else -> {
                val scriptTags = doc.select("script[type='text/javascript']")
                var foundId: String? = null

                for (script in scriptTags) {
                    val scriptData = script.data()
                    if (scriptData.contains("\"eid\":")) {
                        foundId = scriptData.substringAfter("\"eid\":\"").substringBefore("\"")
                        break
                    } else if (scriptData.contains("\"episodeId\":")) {
                        foundId = scriptData.substringAfter("\"episodeId\":\"").substringBefore("\"")
                        break
                    } else if (scriptData.contains("episode_id")) {
                        val match = Regex("episode_id['\"]?\\s*:\\s*['\"]?(\\d+)").find(scriptData)
                        foundId = match?.groupValues?.get(1)
                        break
                    }
                }

                foundId ?: throw Exception("Could not find episode ID in page content")
            }
        }

        println("📦 Found episode ID: $episodeId")

        val audioUrl = try {
            val apiUrl = "https://everest.castbox.fm/web/v1/episode?eid=$episodeId"
            println("🌐 Trying primary API...")
            val response = client.get(apiUrl) {
                header("Accept", "application/json")
                header("Origin", "https://castbox.fm")
                header("Referer", "https://castbox.fm/")
            }

            val responseBody = response.body<JsonObject>()
            responseBody["data"]?.jsonObject?.get("audio")?.jsonPrimitive?.content
        } catch (e: Exception) {
            println("⚠️ Primary API failed, trying alternative methods...")
            null
        } ?: try {
            val alternativeApiUrl = "https://api.castbox.fm/v2/episodes/$episodeId"
            println("🌐 Trying alternative API...")
            val response = client.get(alternativeApiUrl) {
                header("Accept", "application/json")
                header("Origin", "https://castbox.fm")
                header("Referer", "https://castbox.fm/")
            }

            val responseBody = response.body<JsonObject>()
            responseBody["audio"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            println("⚠️ Alternative API failed, falling back to meta tags...")
            null
        } ?: run {
            println("🔍 Searching for audio URL in page meta tags...")
            doc.select("meta[property=og:audio]").firstOrNull()?.attr("content")
                ?: doc.select("meta[property=og:audio:secure_url]").firstOrNull()?.attr("content")
                ?: doc.select("audio[src]").firstOrNull()?.attr("src")
                ?: doc.select("source[src*=.mp3]").firstOrNull()?.attr("src")
                ?: throw Exception("Could not find audio URL through any method")
        }

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

        if (!isValidAudioUrl(audioUrl)) {
            throw Exception("Invalid audio URL format")
        }

        return PodcastInfo(title, description, audioUrl)
    }

    private fun cleanDescription(description: String): String {
        return Jsoup.parse(description).text()
    }

    private fun isValidAudioUrl(url: String): Boolean {
        return try {
            val parsedUrl = URL(url)
            val path = parsedUrl.path.lowercase()
            path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac") ||
                    url.contains("audio") || url.contains("media") || url.contains("stream")
        } catch (e: Exception) {
            false
        }
    }
}