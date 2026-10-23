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
| Stuknięcie przycisku `?` | zrzut ekranu i pytanie do Claude |
| Stuknięcie dymka | zamyka dymek |
| Przeciągnięcie | przesuwa przycisk |
| Przytrzymanie | wyłącza Dymek |

Modele:

| Model | Koszt | Uwagi |
| --- | --- | --- |
| Claude Opus 5.5 | płatny | domyślny, najdokładniejszy |
| Claude Sonnet 5.5 | płatny | szybszy |
| Claude Haiku 5.5 | płatny, najtańszy | najszybszy z Claude |
| Gemini 3.8 Flash | darmowy | z limitami Google |
| Gemini 3.5 Flash-Lite | darmowy | najszybszy, z limitami Google |

W quizie na czas warto wybrać szybszy model.

## Jak to działa

- `MainActivity` zapisuje klucz i model w prywatnych ustawieniach aplikacji (bez kopii zapasowej w chmurze)
  i prosi o zgody.
- `OverlayService` to usługa pierwszoplanowa z `MediaProjection`: trzyma ostatnią klatkę ekranu, a po stuknięciu
  chowa przycisk, bierze klatkę, zmniejsza ją do 1568 px i wysyła jako JPEG.
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
