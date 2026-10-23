package io.github.romangumeniuk.dymek

import android.graphics.Bitmap
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

/** Claude przez oficjalne SDK Anthropic (platne z konta Claude). */
class ClaudeAnswerer(apiKey: String, private val model: ModelChoice) : Answerer {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    override fun answer(screen: Bitmap): String {
        val builder = MessageCreateParams.builder()
            .model(model.id)
            // Opus zawsze mysli, wiec potrzebuje miejsca; pozostale odpowiadaja od razu
            .maxTokens(if (model == ModelChoice.OPUS) 4000L else 512L)
            .system(Answerer.SYSTEM_PROMPT)
            // niski wysilek = szybka odpowiedz, a w quizie liczy sie czas
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                    .data(Answerer.toJpegBase64(screen))
                                    .build()
                            )
                            .build()
                    ),
                    ContentBlockParam.ofText(TextBlockParam.builder().text(Answerer.USER_PROMPT).build()),
                )
            )
        when (model) {
            // bez myslenia odpowiedz przychodzi najszybciej
            ModelChoice.HAIKU ->
                builder.putAdditionalBodyProperty("thinking", JsonValue.from(mapOf("type" to "disabled")))
            ModelChoice.SONNET ->
                builder.putAdditionalBodyProperty("thinking", JsonValue.from(mapOf("type" to "between_tools")))
            else -> {
                // Opus nie wylacza myslenia; gdy odmowi, serwer sam ponawia na innym modelu
                builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }
        }

        val response = try {
            client.messages().create(builder.build())
        } catch (e: UnauthorizedException) {
            return "Zły klucz API Claude. Sprawdź go w aplikacji Dymek."
        } catch (e: PermissionDeniedException) {
            return "Klucz nie ma dostępu do tego modelu."
        } catch (e: RateLimitException) {
            return "Za dużo zapytań, spróbuj za chwilę."
        } catch (e: BadRequestException) {
            return "Błąd zapytania: ${e.message?.take(160)}"
        } catch (e: AnthropicServiceException) {
            return "Błąd API (${e.statusCode()}). Spróbuj ponownie."
        } catch (e: AnthropicIoException) {
            return "Brak połączenia z internetem."
        }

        if (response.stopReason().map { it.toString() }.orElse("") == "refusal") {
            return "Claude odmówił odpowiedzi na to pytanie."
        }
        val text = response.content()
            .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString("\n")
            .trim()
        return text.ifEmpty { "Brak odpowiedzi." }
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
