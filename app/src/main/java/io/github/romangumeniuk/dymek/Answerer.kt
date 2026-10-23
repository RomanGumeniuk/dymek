package io.github.romangumeniuk.dymek

import android.graphics.Bitmap
import android.util.Base64
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
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Wysyla zrzut ekranu do Claude i zwraca krotka odpowiedz do dymka. */
class Answerer(apiKey: String, private val model: ModelChoice) : AutoCloseable {
    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    fun answer(screen: Bitmap): String {
        val builder = MessageCreateParams.builder()
            .model(model.id)
            .maxTokens(4000L)
            .system(SYSTEM_PROMPT)
            // niski wysilek = szybka odpowiedz, a w quizie liczy sie czas
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .addUserMessageOfBlockParams(
                listOf(
                    ContentBlockParam.ofImage(
                        ImageBlockParam.builder()
                            .source(
                                Base64ImageSource.builder()
                                    .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                    .data(toJpegBase64(screen))
                                    .build()
                            )
                            .build()
                    ),
                    ContentBlockParam.ofText(
                        TextBlockParam.builder().text("Odpowiedz na pytanie z tego ekranu.").build()
                    ),
                )
            )
        if (model.serverFallback) {
            // gdy model odmowi, serwer sam ponawia na innym modelu
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }

        val response = try {
            client.messages().create(builder.build())
        } catch (e: UnauthorizedException) {
            return "Zły klucz API. Sprawdź go w aplikacji Dymek."
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

    override fun close() = client.close()

    private fun toJpegBase64(src: Bitmap): String {
        // dluzszy bok do 1568 px: tyle model i tak widzi, a wysylka jest szybsza
        val longSide = max(src.width, src.height)
        val scaled = if (longSide > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / longSide
            Bitmap.createScaledBitmap(src, (src.width * k).roundToInt(), (src.height * k).roundToInt(), true)
        } else {
            src
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (scaled !== src) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private companion object {
        const val MAX_SIDE = 1568

        val SYSTEM_PROMPT = """
            Dostajesz zrzut ekranu telefonu. Zwykle jest na nim pytanie, np. z quizu, testu wiedzy albo zagadki.
            Odpowiedz na nie jak najkrócej, bo odpowiedź pokaże się w małym dymku na ekranie:
            - przy pytaniu z opcjami podaj literę lub numer i treść poprawnej opcji,
            - w innym wypadku samą odpowiedź (słowo, liczbę, nazwę) i najwyżej jedno krótkie zdanie wyjaśnienia,
            - bez wstępów i bez powtarzania pytania.
            Pisz w języku, w którym jest pytanie. Jeśli na ekranie nie ma żadnego pytania, napisz w kilku słowach, co widzisz.
        """.trimIndent()
    }
}
