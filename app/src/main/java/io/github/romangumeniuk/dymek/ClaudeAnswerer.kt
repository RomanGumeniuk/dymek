package io.github.romangumeniuk.dymek

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.WebSearchTool20250305
import com.anthropic.models.messages.WebSearchTool20260209

/** Claude przez oficjalne SDK Anthropic (platne z konta Claude). */
class ClaudeAnswerer(apiKey: String, private val model: ModelChoice) : Answerer {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    override fun ask(image: String, mode: Mode, unsure: Answer?): Answer {
        val search = unsure != null
        val thinks = mode.think || model == ModelChoice.OPUS
        val builder = MessageCreateParams.builder()
            .model(model.id)
            // myslenie i wyszukiwanie dokladaja kilka krokow, wiec potrzebuja miejsca
            .maxTokens(
                when {
                    thinks -> 8000L
                    search -> 2048L
                    else -> 512L
                }
            )
            .system(Answerer.SYSTEM_PROMPT)
            // niski wysilek = szybka odpowiedz, a w quizie liczy sie czas; tryb Dokladny mysli dluzej
            .outputConfig(
                OutputConfig.builder()
                    .effort(if (mode.think) OutputConfig.Effort.MEDIUM else OutputConfig.Effort.LOW)
                    .build()
            )
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                    .data(image)
                                    .build()
                            )
                            .build()
                    ),
                    ContentBlockParam.ofText(
                        TextBlockParam.builder()
                            .text(unsure?.let { Answerer.searchPrompt(it, mode) } ?: Answerer.USER_PROMPT)
                            .build()
                    ),
                )
            )
        if (search) {
            // Haiku zna tylko podstawowa wyszukiwarke, Sonnet i Opus nowsza, z filtrowaniem wynikow
            if (model == ModelChoice.HAIKU) {
                builder.addTool(WebSearchTool20250305.builder().maxUses(mode.maxSearches.toLong()).build())
            } else {
                builder.addTool(WebSearchTool20260209.builder().maxUses(mode.maxSearches.toLong()).build())
            }
        }
        when {
            // mysli sam, ile trzeba (domyslne myslenie adaptacyjne); gdy odmowi, serwer ponawia na innym
            // modelu, czego Haiku nie ma
            thinks -> if (model != ModelChoice.HAIKU) {
                builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
            // bez myslenia odpowiedz przychodzi najszybciej
            model == ModelChoice.HAIKU ->
                builder.putAdditionalBodyProperty("thinking", JsonValue.from(mapOf("type" to "disabled")))
            else ->
                builder.putAdditionalBodyProperty("thinking", JsonValue.from(mapOf("type" to "between_tools")))
        }

        val response = try {
            client.messages().create(builder.build())
        } catch (e: UnauthorizedException) {
            return Answer.error("Zły klucz API Claude. Sprawdź go w aplikacji Dymek.")
        } catch (e: PermissionDeniedException) {
            return Answer.error("Klucz nie ma dostępu do tego modelu.")
        } catch (e: RateLimitException) {
            return Answer.error("Za dużo zapytań, spróbuj za chwilę.")
        } catch (e: BadRequestException) {
            return Answer.error("Błąd zapytania: ${e.message?.take(160)}")
        } catch (e: AnthropicServiceException) {
            return Answer.error("Błąd API (${e.statusCode()}). Spróbuj ponownie.")
        } catch (e: AnthropicIoException) {
            return Answer.error("Brak połączenia z internetem.")
        }

        when (response.stopReason().map { it.toString() }.orElse("")) {
            "refusal" -> return Answer.error("Claude odmówił odpowiedzi na to pytanie.")
            // wyszukiwanie nie skonczylo sie w jednej turze: zostaje niepewna odpowiedz bez internetu
            "pause_turn" -> return unsure ?: Answer.error("Brak odpowiedzi.")
        }
        val content = response.content()
        // przy wyszukiwaniu odpowiedz bywa pocieta na kilka blokow z cytatami, wiec laczymy bez odstepow
        val text = content
            .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString("")
        // kazde uzycie narzedzia po stronie serwera to tu wyszukiwanie (albo filtrowanie jego wynikow)
        return Answer.parse(text).copy(searches = content.count { it.isServerToolUse() })
    }

    override fun warmUp() {
        // darmowe zapytanie o model: zostawia otwarte polaczenie w puli OkHttp
        try {
            client.models().retrieve(model.id)
        } catch (e: RuntimeException) {
            // rozgrzewka nie jest konieczna
        }
    }

    override fun close() = client.close()
}
