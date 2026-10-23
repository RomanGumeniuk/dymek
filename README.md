# Dymek

Aplikacja na Androida: pływający przycisk nad innymi aplikacjami. Stukasz go, telefon robi zrzut ekranu,
Claude czyta pytanie (np. z quizu) i pokazuje krótką odpowiedź w dymku obok przycisku.

## Instalacja

1. Pobierz `dymek.apk` z zakładki [Releases](../../releases/latest) i zainstaluj
   (Android poprosi o zgodę na instalację z nieznanego źródła).
2. Wejdź na [platform.claude.com/settings/keys](https://platform.claude.com/settings/keys), utwórz klucz API
   i wklej go w aplikacji. Zapytania są płatne z Twojego konta Claude.
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

Modele: **Opus 5.5** (domyślny, najdokładniejszy), **Sonnet 5.5** (szybszy), **Haiku 5.5** (najszybszy i najtańszy).
W quizie na czas warto przełączyć na Haiku albo Sonnet.

## Jak to działa

- `MainActivity` zapisuje klucz i model w prywatnych ustawieniach aplikacji (bez kopii zapasowej w chmurze)
  i prosi o zgody.
- `OverlayService` to usługa pierwszoplanowa z `MediaProjection`: trzyma ostatnią klatkę ekranu, a po stuknięciu
  chowa przycisk, bierze klatkę, zmniejsza ją do 1568 px i wysyła jako JPEG.
- `Answerer` woła Messages API przez oficjalne SDK `com.anthropic:anthropic-java`. Klucz idzie tylko do
  `api.anthropic.com`.

Ograniczenia: aplikacje blokujące zrzuty ekranu (bankowe, niektóre z DRM) dają czarny obraz.
Na iOS taka aplikacja nie jest możliwa (system nie pozwala na nakładki i zrzuty innych aplikacji).

## Budowanie

```bash
./gradlew assembleRelease
```

Wymaga JDK 17 i Android SDK (API 36). Każdy push na `main` buduje APK w GitHub Actions,
a tag `v*` tworzy wydanie z plikiem `dymek.apk`. Podpis wydania bierze się z sekretów repo
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (lokalnie z `keystore.properties`).
