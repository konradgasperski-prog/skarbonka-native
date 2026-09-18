# Skarbonka — wersje natywne (macOS, Windows, Android/HyperOS)

Ten folder zawiera kod źródłowy do zbudowania Skarbonki jako:
- **prawdziwej aplikacji desktopowej** dla macOS i Windows 11+ (bez okna przeglądarki, ikonka w Docku/pasku zadań) — folder `desktop/`
- **aplikacji na Androida / HyperOS 3-4** z widgetem salda na ekranie głównym — folder `android/`

Kompilacja odbywa się **automatycznie w chmurze przez GitHub Actions** (`.github/workflows/build.yml`) —
nie musisz instalować Xcode, Visual Studio ani Android Studio na swoim komputerze.

## Krok po kroku

1. Załóż darmowe konto na [github.com](https://github.com) (jeśli jeszcze nie masz).
2. Utwórz nowe, **puste** repozytorium (przycisk "New repository"), np. o nazwie `skarbonka-native`.
3. Na swoim komputerze rozpakuj ten plik zip, wejdź do folderu w terminalu i wykonaj:
   ```
   cd skarbonka-native
   git init
   git add .
   git commit -m "Skarbonka - pierwsza wersja"
   git branch -M main
   git remote add origin https://github.com/TWOJA-NAZWA/skarbonka-native.git
   git push -u origin main
   ```
   (Adres w linii `git remote add origin` znajdziesz na stronie swojego nowego repozytorium na GitHubie.)
4. Wejdź na stronę repozytorium na GitHubie → zakładka **Actions**. Zobaczysz uruchomiony workflow
   "Build Skarbonka (macOS, Windows, Android)". Budowanie trwa ok. 5-15 minut.
5. Po zakończeniu, na dole strony danego przebiegu (workflow run) znajdziesz sekcję **Artifacts** z trzema plikami:
   - `skarbonka-macos` — w środku plik `.dmg` (instalator) i/lub `.app`
   - `skarbonka-windows` — w środku plik `.msi` i/lub `.exe`
   - `skarbonka-android` — w środku plik `.apk`

## Instalacja

**macOS:** pobierz i otwórz `.dmg`, przeciągnij Skarbonkę do Aplikacji. Ponieważ aplikacja nie jest podpisana
certyfikatem Apple (to kosztuje 99$/rok), przy pierwszym uruchomieniu system pokaże ostrzeżenie
"nieznany deweloper". Kliknij prawym przyciskiem na ikonę → **Otwórz** → potwierdź "Otwórz" w oknie dialogowym
(albo: Ustawienia systemowe → Prywatność i bezpieczeństwo → "Otwórz mimo to").

**Windows 11+:** uruchom `.msi` lub `.exe`. Windows SmartScreen może pokazać "Windows ochronił Twój komputer"
(bo plik nie jest podpisany certyfikatem, który kosztuje pieniądze) — kliknij **Więcej informacji** → **Uruchom mimo to**.

**Android / HyperOS 3-4:** skopiuj plik `.apk` na telefon, włącz w ustawieniach "Instaluj z nieznanych źródeł"
dla aplikacji, którą otwierasz plik (np. Menedżer plików), i zainstaluj. Po otwarciu aplikacji przytrzymaj
palec na pustym miejscu ekranu głównego → Widgety → Skarbonka → przeciągnij widget salda na ekran.
Widget aktualizuje się automatycznie po każdej zmianie salda w aplikacji.

## Co dalej

To jest pierwsza, działająca wersja. Jeśli GitHub Actions pokaże błąd budowania (czerwony X) —
skopiuj mi treść błędu z logów, a poprawię kod. Nie mam możliwości samodzielnego skompilowania
i przetestowania plików .app/.exe/.apk w tej rozmowie (mój "warsztat" to Linux bez macOS/Windows/Androida),
więc realne budowanie i pierwsze uruchomienie zawsze będzie po Twojej stronie — ale poprawki kodu mogę
robić stąd na bieżąco.
