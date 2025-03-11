package com.castboxdownloader.bot

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import java.net.URL

class TelegramBot(private val token: String) {
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

        val keyboard = JsonObject(mapOf(
            "inline_keyboard" to JsonArray(listOf(
                JsonArray(listOf(
                    JsonObject(mapOf(
                        "text" to JsonPrimitive("English 🇺🇸"),
                        "callback_data" to JsonPrimitive("lang_en")
                    )),
                    JsonObject(mapOf(
                        "text" to JsonPrimitive("فارسی 🇮🇷"),
                        "callback_data" to JsonPrimitive("lang_fa")
                    ))
                ))
            ))
        ))
        
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
                setBody(JsonObject(mapOf(
                    "callback_query_id" to JsonPrimitive(callbackId),
                    "text" to JsonPrimitive(text)
                )))
            }
        } catch (e: Exception) {
            println("❌ Error answering callback query: ${e.message}")
        }
    }

    private fun cleanDescription(description: String): String {
        return Jsoup.parse(description).text()
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

        if (!isValidCastboxUrl(url)) {
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
            return
        }

        try {
            val fetchingMessage = when (lang) {
                "fa" -> "🔍 در حال دریافت اطلاعات پادکست..."
                else -> "🔍 Fetching podcast details..."
            }
            val fetchMsgId = sendMessage(chatId, fetchingMessage)

            val podcastInfo = extractPodcastInfo(url)
            
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

    private suspend fun sendAudio(chatId: Long, fileName: String, audioData: ByteArray, podcastInfo: PodcastInfo) {
        println("📤 Sending audio file to chat ID: $chatId")

        val caption = """
            🎙️ *${podcastInfo.title}*
            
            📝 ${podcastInfo.description}
            
            🔗 Direct link: ${podcastInfo.audioUrl}
        """.trimIndent()

        val response = client.post("$baseUrl/sendAudio") {
            setBody(MultiPartFormDataContent(formData {
                append("chat_id", chatId.toString())
                append("caption", caption)
                append("parse_mode", "Markdown")
                append("audio", audioData, Headers.build {
                    append(HttpHeaders.ContentType, "audio/mpeg")
                    append(HttpHeaders.ContentDisposition, "filename=$fileName")
                })
            }))
        }

        println("📨 Telegram API response for audio: ${response.status}")

        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.body<String>()
            println("❌ Telegram API Error: $errorBody")
            throw Exception("Failed to send audio file")
        }
    }

    private suspend fun deleteMessage(chatId: Long, messageId: Int) {
        if (messageId == 0) return

        try {
            val response = client.post("$baseUrl/deleteMessage") {
                contentType(ContentType.Application.Json)
                setBody(JsonObject(
                    mapOf(
                        "chat_id" to JsonPrimitive(chatId),
                        "message_id" to JsonPrimitive(messageId)
                    )
                ))
            }

            println("🗑️ Delete message response: ${response.status}")
        } catch (e: Exception) {
            println("❌ Error deleting message: ${e.message}")
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
        println("🔍 Analyzing URL: $url")
        
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

        println("episode id: $episodeId")

        val audioUrl = try {
            val apiUrl = "https://everest.castbox.fm/web/v1/episode?eid=$episodeId"
            val response = client.get(apiUrl) {
                header("Accept", "application/json")
                header("Origin", "https://castbox.fm")
                header("Referer", "https://castbox.fm/")
            }
            
            val responseBody = response.body<JsonObject>()
            responseBody["data"]?.jsonObject?.get("audio")?.jsonPrimitive?.content
        } catch (e: Exception) {
            println("primary api failed, trying alternative methods: ${e.message}")
            null
        } ?: try {
            val alternativeApiUrl = "https://api.castbox.fm/v2/episodes/$episodeId"
            val response = client.get(alternativeApiUrl) {
                header("Accept", "application/json")
                header("Origin", "https://castbox.fm")
                header("Referer", "https://castbox.fm/")
            }
            
            val responseBody = response.body<JsonObject>()
            responseBody["audio"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            println("Alternative API failed, falling back to meta tags: ${e.message}")
            null
        } ?: run {
            doc.select("meta[property=og:audio]").firstOrNull()?.attr("content")
                ?: doc.select("meta[property=og:audio:secure_url]").firstOrNull()?.attr("content")
                ?: doc.select("audio[src]").firstOrNull()?.attr("src")
                ?: doc.select("source[src*=.mp3]").firstOrNull()?.attr("src")
                ?: throw Exception("Could not find audio URL through any method")
        }

        println("Found Audio URL: $audioUrl")

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

    data class PodcastInfo(
        val title: String,
        val description: String,
        val audioUrl: String
    )
} 