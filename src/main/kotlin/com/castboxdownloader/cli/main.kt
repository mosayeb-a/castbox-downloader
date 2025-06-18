package com.castboxdownloader.cli

import com.castboxdownloader.service.CastboxService
import kotlinx.coroutines.runBlocking

fun main() {
    println("""
        📱 Castbox Downloader CLI
        
        Enter a Castbox URL (or 'exit' to quit)
        Example URLs:
            https://castbox.fm/episode/example-id123456
            https://castbox.fm/vb/123456
            
        > """.trimIndent()
    )

    val downloader = CastboxDownloader(CastboxService())

    while (true) {
        val input = readlnOrNull()?.trim()

        if (input.isNullOrEmpty() || input.lowercase() == "exit") {
            println("👋 Goodbye!")
            break
        }

        runBlocking {
            downloader.downloadPodcast(input)
        }
        println("\nEnter another URL (or 'exit' to quit):\n> ")
    }
}