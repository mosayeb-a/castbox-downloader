package com.castboxdownloader.bot

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import com.castboxdownloader.bot.plugins.configureRouting
import com.castboxdownloader.service.CastboxService

fun main() {
    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    val botToken = System.getenv("TELEGRAM_BOT_TOKEN")
        ?: throw IllegalStateException("TELEGRAM_BOT_TOKEN is null")

    val castboxService = CastboxService()
    val telegramBot = TelegramBot(botToken, castboxService)

    configureRouting(telegramBot)
}