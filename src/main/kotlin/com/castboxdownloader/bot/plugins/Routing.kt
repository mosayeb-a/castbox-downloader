package com.castboxdownloader.bot.plugins

import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import com.castboxdownloader.bot.TelegramBot

fun Application.configureRouting(telegramBot: TelegramBot) {
    routing {
        post("/webhook") {
            val update = call.receive<JsonObject>()
            telegramBot.handleUpdate(update)
            call.respond(JsonObject(mapOf("status" to JsonPrimitive("ok"))))
        }

        get("/") {
            call.respondText("CastBox Downloader Bot is running!")
        }
    }
} 