package com.castboxdownloader.cli

import com.castboxdownloader.service.CastboxService

class CastboxDownloader(private val castboxService: CastboxService) {
    suspend fun downloadPodcast(url: String) {
        try {
            println("🔍 Analyzing URL: $url")
            val podcastInfo = castboxService.extractPodcastInfo(url)

            println("""
                🎙️ ${podcastInfo.title}
                
                📝 ${podcastInfo.description}
                
                📥 Download link:
                ${podcastInfo.audioUrl}
            """.trimIndent())

        } catch (e: IllegalArgumentException) {
            println("""
                ❌ Invalid Castbox URL
                
                The URL should be in one of these formats:
                1. https://castbox.fm/episode/...
                2. https://castbox.fm/vb/...
            """.trimIndent())
        } catch (e: Exception) {
            println("❌ Error: ${e.message}")
        }
    }
}