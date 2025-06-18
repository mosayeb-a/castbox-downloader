package com.castboxdownloader.bot

import com.castboxdownloader.service.CastboxService
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

class TelegramBot(
    token: String,
    private val castboxService: CastboxService
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json()
        }
    }

    private val baseUrl = "https://api.telegram.org/bot$token"
    private val userLanguages = mutableMapOf<Long, String>()

    suspend fun handleUpdate(update: JsonObject) {
        val message = update["message"]?.jsonObject
        val callback = update["callback_query"]?.jsonObject

        when {
            callback != null -> {
                val data = callback["data"]?.jsonPrimitive?.content
                val chatId = callback["message"]?.jsonObject?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.long

                if (data != null && chatId != null) {
                    when (data) {
                        "lang_en" -> {
                            userLanguages[chatId] = "en"
                            answerCallbackQuery(callback["id"]?.jsonPrimitive?.content ?: "", "🌐 Language set to English")
                            deleteMessage(chatId, callback["message"]?.jsonObject?.get("message_id")?.jsonPrimitive?.int ?: 0)
                            sendWelcomeMessageAfterLang(chatId, "en")
                        }
                        "lang_fa" -> {
                            userLanguages[chatId] = "fa"
                            answerCallbackQuery(callback["id"]?.jsonPrimitive?.content ?: "", "🌐 زبان به فارسی تغییر کرد")
                            deleteMessage(chatId, callback["message"]?.jsonObject?.get("message_id")?.jsonPrimitive?.int ?: 0)
                            sendWelcomeMessageAfterLang(chatId, "fa")
                        }
                    }
                }
                return
            }
            message != null -> {
                val text = message["text"]?.jsonPrimitive?.content ?: return
                val chatId = message["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.long ?: return

                when {
                    text.startsWith("/start") -> sendWelcomeMessage(chatId)
                    text == "/fa" -> {
                        userLanguages[chatId] = "fa"
                        sendWelcomeMessageAfterLang(chatId, "fa")
                    }
                    text == "/en" -> {
                        userLanguages[chatId] = "en"
                        sendWelcomeMessageAfterLang(chatId, "en")
                    }
                    text.contains("castbox.fm") -> handleCastboxUrl(chatId, text)
                    else -> {
                        val lang = userLanguages[chatId] ?: "en"
                        val helpMessage = when (lang) {
                            "fa" -> """
                                🎧 لطفا لینک پادکست کست‌باکس را ارسال کنید
                                مثال: https://castbox.fm/episode/...
                            """.trimIndent()
                            else -> """
                                🎧 Please send me a Castbox podcast link
                                Example: https://castbox.fm/episode/...
                            """.trimIndent()
                        }
                        sendMessage(chatId, helpMessage)
                    }
                }
            }
        }
    }

    private suspend fun sendWelcomeMessage(chatId: Long) {
        val welcomeText = """
            Welcome to CastBox Downloader Bot! 🎙️
            به ربات دانلودر کست‌باکس خوش آمدید! 🎙️
            
            Please select your language:
            :لطفا زبان خود را انتخاب کنید
        """.trimIndent()

        val keyboard = JsonObject(
            mapOf(
                "inline_keyboard" to JsonArray(
                    listOf(
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "text" to JsonPrimitive("English 🇺🇸"),
                                        "callback_data" to JsonPrimitive("lang_en")
                                    )
                                ),
                                JsonObject(
                                    mapOf(
                                        "text" to JsonPrimitive("فارسی 🇮🇷"),
                                        "callback_data" to JsonPrimitive("lang_fa")
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )

        sendMessage(chatId, welcomeText, keyboard)
    }

    private suspend fun sendWelcomeMessageAfterLang(chatId: Long, lang: String) {
        val welcomeText = when (lang) {
            "fa" -> """
                🎙️ به ربات دانلودر کست‌باکس خوش آمدید!
                
                راهنمای استفاده:
                1. کافیست لینک کست‌باکس را برای من ارسال کنید
                2. من لینک دانلود را استخراج میکنم
                3. برای دانلود فایل از ربات های @uploadbot یا @urluploadxbot استفاده کنید
                
                برای تغییر زبان: /en - English
            """.trimIndent()
            else -> """
                🎙️ Welcome to CastBox Downloader Bot!
                
                How to use:
                1. Just send me any Castbox URL
                2. I'll extract the download link
                3. Use @uploadbot or @urluploadxbot to download the file
                
                To change language: /fa - فارسی
            """.trimIndent()
        }

        sendMessage(chatId, welcomeText)
    }

    private suspend fun answerCallbackQuery(callbackId: String, text: String) {
        try {
            client.post("$baseUrl/answerCallbackQuery") {
                contentType(ContentType.Application.Json)
                setBody(
                    JsonObject(
                        mapOf(
                            "callback_query_id" to JsonPrimitive(callbackId),
                            "text" to JsonPrimitive(text)
                        )
                    )
                )
            }
        } catch (e: Exception) {
            println("❌ Error answering callback query: ${e.message}")
        }
    }

    private suspend fun sendMessage(chatId: Long, text: String, replyMarkup: JsonObject? = null): Int {
        println("📤 Sending message to chat ID: $chatId")

        val params = buildMap<String, JsonElement> {
            put("chat_id", JsonPrimitive(chatId))
            put("text", JsonPrimitive(text))
            put("parse_mode", JsonPrimitive("Markdown"))
            if (replyMarkup != null) {
                put("reply_markup", replyMarkup)
            }
        }

        try {
            val response = client.post("$baseUrl/sendMessage") {
                contentType(ContentType.Application.Json)
                setBody(JsonObject(params))
            }

            println("📨 Telegram API response: ${response.status}")

            if (response.status != HttpStatusCode.OK) {
                val errorBody = response.body<String>()
                println("❌ Telegram API Error: $errorBody")
                return 0
            }

            val responseJson = response.body<JsonObject>()
            return responseJson["result"]?.jsonObject?.get("message_id")?.jsonPrimitive?.int ?: 0
        } catch (e: Exception) {
            println("❌ Error sending message: ${e.message}")
            return 0
        }
    }

    private suspend fun handleCastboxUrl(chatId: Long, text: String) {
        val url = text.split(" ").find { it.contains("castbox.fm") } ?: return
        val lang = userLanguages[chatId] ?: "en"

        try {
            val fetchingMessage = when (lang) {
                "fa" -> "🔍 در حال دریافت اطلاعات پادکست..."
                else -> "🔍 Fetching podcast details..."
            }
            val fetchMsgId = sendMessage(chatId, fetchingMessage)

            // Use CastboxService to extract podcast info
            val podcastInfo = castboxService.extractPodcastInfo(url)

            deleteMessage(chatId, fetchMsgId)

            val message = when (lang) {
                "fa" -> """
                    🎙️ *${podcastInfo.title}*
                    
                    📝 ${podcastInfo.description}
                    
                    📥 برای دانلود:
                    1. لینک زیر را کپی کنید
                    2. به یکی از ربات های @uploadbot یا @urluploadxbot بروید
                    3. لینک را برای ربات ارسال کنید
                    
                    🔗 لینک دانلود:
                    `${podcastInfo.audioUrl}`
                """.trimIndent()
                else -> """
                    🎙️ *${podcastInfo.title}*
                    
                    📝 ${podcastInfo.description}
                    
                    📥 To download:
                    1. Copy the link below
                    2. Go to @uploadbot or @urluploadxbot
                    3. Send the link to the bot
                    
                    🔗 Download link:
                    `${podcastInfo.audioUrl}`
                """.trimIndent()
            }

            sendMessage(chatId, message)
        } catch (e: IllegalArgumentException) {
            val errorMessage = when (lang) {
                "fa" -> """
                    ❌ لینک کست‌باکس نامعتبر است
                    
                    لینک باید به یکی از این فرمت ها باشد:
                    1. https://castbox.fm/episode/...
                    2. https://castbox.fm/vb/...
                """.trimIndent()
                else -> """
                    ❌ Invalid Castbox URL
                    
                    The URL should be in one of these formats:
                    1. https://castbox.fm/episode/...
                    2. https://castbox.fm/vb/...
                """.trimIndent()
            }
            sendMessage(chatId, errorMessage)
        } catch (e: Exception) {
            val errorMessage = when (lang) {
                "fa" -> when {
                    e.message?.contains("Could not find audio URL") == true ->
                        "❌ فایل صوتی پیدا نشد. این قسمت ممکن است پریمیوم باشد یا دیگر در دسترس نیست."
                    e.message?.contains("Invalid audio URL format") == true ->
                        "❌ فرمت فایل صوتی پشتیبانی نمیشود یا لینک نامعتبر است."
                    e.message?.contains("episode ID") == true ->
                        "❌ شناسه قسمت پیدا نشد. این قسمت ممکن است حذف شده باشد."
                    else -> "❌ خطا در دریافت پادکست: ${e.message}"
                }
                else -> when {
                    e.message?.contains("Could not find audio URL") == true ->
                        "❌ Could not find the audio file. This episode might be premium content or no longer available."
                    e.message?.contains("Invalid audio URL format") == true ->
                        "❌ The audio file format is not supported or the URL is invalid."
                    e.message?.contains("episode ID") == true ->
                        "❌ Could not extract episode information. The episode might have been removed."
                    else -> "❌ An error occurred while fetching the podcast: ${e.message}"
                }
            }

            println("Error processing URL $url: ${e.message}")
            sendMessage(chatId, errorMessage)
        }
    }

    private suspend fun deleteMessage(chatId: Long, messageId: Int) {
        if (messageId == 0) return

        try {
            val response = client.post("$baseUrl/deleteMessage") {
                contentType(ContentType.Application.Json)
                setBody(
                    JsonObject(
                        mapOf(
                            "chat_id" to JsonPrimitive(chatId),
                            "message_id" to JsonPrimitive(messageId)
                        )
                    )
                )
            }

            println("🗑️ Delete message response: ${response.status}")
        } catch (e: Exception) {
            println("❌ Error deleting message: ${e.message}")
        }
    }
}