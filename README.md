# Dymek

Aplikacja na Androida: pływający przycisk nad innymi aplikacjami. Stukasz go, telefon robi zrzut ekranu,
AI czyta pytanie (np. z quizu) i pokazuje krótką odpowiedź w dymku obok przycisku.
Do wyboru płatny **Claude** albo darmowy **Gemini**.

## Instalacja

1. Pobierz `dymek.apk` z zakładki [Releases](../../releases/latest) i zainstaluj
   (Android poprosi o zgodę na instalację z nieznanego źródła).
2. Wklej w aplikacji klucz API jednego z dostawców:
   - **Gemini (darmowy):** klucz z [aistudio.google.com/apikey](https://aistudio.google.com/apikey).
     Darmowy plan ma limit zapytań na minutę i na dzień, a Google może używać przesłanych zrzutów do ulepszania usług.
   - **Claude (płatny):** klucz z [platform.claude.com/settings/keys](https://platform.claude.com/settings/keys).
     Zapytania są płatne z Twojego konta Claude.
3. Wybierz model i stuknij **Włącz dymek**. Za pierwszym razem Android poprosi o:
   - wyświetlanie nad innymi aplikacjami,
   - powiadomienia (tam jest przycisk „Wyłącz”),
   - nagrywanie / udostępnianie ekranu (wybierz cały ekran).

## Obsługa

| Gest | Co robi |
| --- | --- |
| Stuknięcie przycisku `?` | zrzut ekranu i pytanie do AI |
| Stuknięcie dymka | zamyka dymek |
| Przeciągnięcie | przesuwa przycisk |
| Przeciągnięcie na środek ekranu (na `✕`) | zamyka całą aplikację, także z listy ostatnich |
| Przytrzymanie | wyłącza Dymek |

W ustawieniach są dwie opcje przycisku (działają od razu, także przy włączonym dymku):

- **Mniej widoczny:** mały, szary i półprzezroczysty przycisk zamiast pomarańczowego.
- **Przeciągnięcie na środek ekranu zamyka całą aplikację:** domyślnie włączone. W czasie przeciągania
  na środku ekranu pojawia się kółko z `✕`; po upuszczeniu na nim dymek się wyłącza, a aplikacja zamyka.

Modele, od najszybszego:

| Model | Koszt | Uwagi |
| --- | --- | --- |
| Gemini 3.5 Flash-Lite | darmowy | domyślny, najszybszy, z limitami Google |
| Claude Haiku 5.5 | płatny, najtańszy | bardzo szybki, bez myślenia |
| Gemini 3.8 Flash | darmowy | dokładniejszy, z limitami Google |
| Claude Sonnet 5.5 | płatny | szybki i dokładny, bez myślenia |
| Claude Opus 5.5 | płatny | najdokładniejszy, wolniejszy |

Pod dymkiem widać czas od stuknięcia do odpowiedzi, więc łatwo porównać modele.

## Szybkość

- Prompt jest pod quizy na czas, najczęściej o grach: model podaje samą odpowiedź, bez wyjaśnień.
- Połączenie z API otwiera się zawczasu (przy starcie, co 45 s i w chwili dotknięcia przycisku),
  więc pytanie nie czeka na zestawienie TLS.
- Zrzut jest zmniejszany do 1200 px, a szybkie modele odpowiadają bez myślenia.

## Jak to działa

- `MainActivity` zapisuje klucz i model w prywatnych ustawieniach aplikacji (bez kopii zapasowej w chmurze)
  i prosi o zgody.
- `OverlayService` to usługa pierwszoplanowa z `MediaProjection`: trzyma ostatnią klatkę ekranu, a po stuknięciu
  chowa przycisk, bierze klatkę, zmniejsza ją do 1200 px i wysyła jako JPEG.
- `ClaudeAnswerer` woła Messages API przez oficjalne SDK `com.anthropic:anthropic-java`
  (klucz idzie tylko do `api.anthropic.com`).
- `GeminiAnswerer` woła REST `generateContent` (klucz w nagłówku, tylko do `generativelanguage.googleapis.com`).

Ograniczenia: aplikacje blokujące zrzuty ekranu (bankowe, niektóre z DRM) dają czarny obraz.
Na iOS taka aplikacja nie jest możliwa (system nie pozwala na nakładki i zrzuty innych aplikacji).

## Budowanie

```bash
./gradlew assembleRelease
```

Wymaga JDK 17 i Android SDK (API 36). Każdy push na `main` buduje APK w GitHub Actions,
a tag `v*` tworzy wydanie z plikiem `dymek.apk`. Podpis wydania bierze się z sekretów repo
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (lokalnie z `keystore.properties`).
