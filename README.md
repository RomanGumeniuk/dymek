# Dymek

Aplikacja na Androida: pływający przycisk nad innymi aplikacjami. Stukasz go w quizie (Kahoot, Mentimeter,
Quizizz i podobne), telefon robi zrzut ekranu, AI czyta pytanie i pokazuje odpowiedź w dymku obok przycisku
albo sama ją klika. Do wyboru płatny **Claude** albo darmowy **Gemini**.

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
| Przeciągnięcie na środek ekranu (na `✕`) | jak w Messengerze: przycisk przyciąga się do krzyżyka, puszczenie zamyka całą aplikację, także z listy ostatnich |
| Przytrzymanie | wyłącza Dymek |

W ustawieniach są dwie opcje przycisku (działają od razu, także przy włączonym dymku):

- **Mniej widoczny:** mały, szary i półprzezroczysty przycisk zamiast pomarańczowego.
- **Przeciągnięcie na środek ekranu zamyka całą aplikację:** domyślnie włączone. W czasie przeciągania
  na środku ekranu pojawia się kółko z `✕`; po upuszczeniu na nim dymek się wyłącza, a aplikacja zamyka.

## Odpowiedzi

- AI podaje pewność w procentach. Bez internetu odpowiedź musi mieć co najmniej 90%, inaczej dymek pyta drugi raz,
  a model musi najpierw wyszukać odpowiedź w internecie (Google Search w Gemini, web search w Claude).
  Po wyszukaniu wystarcza 70%. Można to wyłączyć; w Claude wyszukiwania są dodatkowo płatne.
- Niepewnej odpowiedzi dymek nie pokazuje ani nie klika: zamiast niej „Nie wiem” z pewnością, np. `Nie wiem 55%`
  (w klasycznym wyglądzie `🤷 55%`).
- W Kahoot dymek pokazuje sam kształt poprawnej odpowiedzi: kolorowe ▲ ◆ ● ■ (w klasycznym wyglądzie 🔺 🔷 🟡 🟩).
- **Nowy wygląd dymka** (domyślnie włączony): ciemny, półprzezroczysty, bez emotek poza 🔍; można wrócić do
  klasycznego, białego z pomarańczową ramką.
- `🔍2` przy czasie w dymku znaczy, że odpowiedź wymagała sprawdzenia w internecie, a model wyszukał 2 razy
  (`🔍0`: druga próba była, ale model nie szukał). W trakcie wyszukiwania dymek pokazuje „Szukam w internecie…”.
- Gdy Kahoot pokazuje na telefonie same kształty bez treści pytania, AI nie ma czego przeczytać i nie zgaduje.

## Autoclicker

Opcja **Sam klika pewną odpowiedź** stuka w poprawną opcję za Ciebie, a w Mentimeter także w „Wyślij”.
Szuka na ekranie przycisku z tekstem odpowiedzi (dokładniej niż punkt odczytany przez AI ze zrzutu, który zostaje
jako zapas) i na chwilę pokazuje kółko w miejscu kliknięcia.

- Wymaga włączenia usługi **Dymek: autoclicker** w *Ustawienia → Ułatwienia dostępu*. Na Androidzie 13+
  przy APK spoza sklepu trzeba najpierw w *Ustawienia → Aplikacje → Dymek → ⋮* wybrać
  „Zezwól na ustawienia z ograniczeniami”.
- **Opóźnienie kliknięcia** (domyślnie 1 s) jest za każdym razem losowe: od połowy do półtora podanej
  wartości, liczone od chwili, gdy przyjdzie odpowiedź.
- Usługa odczytuje przyciski aplikacji pod spodem tylko w chwili klikania; niczego nie zapisuje ani nie wysyła.
  Po aktualizacji z wersji 1.6 i starszych trzeba ją włączyć jeszcze raz.

Modele, od najszybszego:

| Model | Koszt | Uwagi |
| --- | --- | --- |
| Gemini 3.5 Flash-Lite | darmowy | domyślny, najszybszy, z limitami Google |
| Claude Haiku 5.5 | płatny, najtańszy | bardzo szybki, bez myślenia |
| Gemini 3.8 Flash | darmowy | dokładniejszy, z limitami Google |
| Claude Sonnet 5.5 | płatny | szybki i dokładny, bez myślenia |
| Claude Opus 5.5 | płatny | najdokładniejszy, wolniejszy |

Pod dymkiem widać czas od stuknięcia do odpowiedzi, więc łatwo porównać modele.
Przycisk **Testuj wybrany model** w aplikacji sprawdza model na wzorcowym pytaniu z Kahoot: czas, poprawność
i trafienie kliknięcia, raz bez wyszukiwania i raz z wyszukiwaniem.

## Szybkość

- Prompt jest pod quizy na czas, najczęściej o grach: model podaje samą odpowiedź, bez wyjaśnień.
- Połączenie z API otwiera się zawczasu (przy starcie, co 45 s i w chwili dotknięcia przycisku),
  więc pytanie nie czeka na zestawienie TLS.
- Zrzut jest zmniejszany do 1200 px, a szybkie modele odpowiadają bez myślenia.

## Jak to działa

- `MainActivity` zapisuje klucz, model i opcje w prywatnych ustawieniach aplikacji (bez kopii zapasowej w chmurze)
  i prosi o zgody.
- `OverlayService` to usługa pierwszoplanowa z `MediaProjection`: trzyma ostatnią klatkę ekranu, a po stuknięciu
  chowa przycisk, bierze klatkę, zmniejsza ją do 1200 px i wysyła jako JPEG.
- `ClaudeAnswerer` woła Messages API przez oficjalne SDK `com.anthropic:anthropic-java`
  (klucz idzie tylko do `api.anthropic.com`).
- `GeminiAnswerer` woła REST `generateContent` (klucz w nagłówku, tylko do `generativelanguage.googleapis.com`).
- Model odpowiada jednym wierszem JSON (`Answer`): pytanie z ekranu, odpowiedź, kształt z Kahoot, punkt
  do kliknięcia w skali 0–1000, ewentualny przycisk zatwierdzenia i pewność w procentach.
- `TapService` to usługa ułatwień dostępu, która klika przez `dispatchGesture`.

Ograniczenia: aplikacje blokujące zrzuty ekranu (bankowe, niektóre z DRM) dają czarny obraz.
Na iOS taka aplikacja nie jest możliwa (system nie pozwala na nakładki i zrzuty innych aplikacji).

## Budowanie

```bash
./gradlew assembleRelease
```

Wymaga JDK 17 i Android SDK (API 36). Każdy push na `main` buduje APK w GitHub Actions,
a tag `v*` tworzy wydanie z plikiem `dymek.apk`. Podpis wydania bierze się z sekretów repo
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (lokalnie z `keystore.properties`).
