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

## iOS (nowość)

Doszedł folder `ios/` — to samo Stash, tym razem jako projekt Xcode (przez Capacitor).

**WAŻNE — przeczytaj zanim zaczniesz:** instalacja na prawdziwym iPhonie wymaga **decyzji**:

- **Za darmo, ale na 7 dni:** otwierasz projekt w Xcode na swoim Macu, podłączasz iPhone kablem,
  logujesz się swoim zwykłym Apple ID (to za darmo) i klikasz Uruchom. Appka zainstaluje się i będzie
  działać przez 7 dni — potem trzeba powtórzyć te same kroki (podłączyć kabel, kliknąć Uruchom).
- **Płatne (99$/rok), ale trwałe:** konto Apple Developer Program pozwala instalować appkę bez
  limitu czasowego, bez podłączania kabla za każdym razem, a docelowo też publikować w App Store.

GitHub Actions **nie może** zainstalować appki na Twoim iPhonie automatycznie — potrafi tylko
sprawdzić, że projekt się poprawnie kompiluje (buduje wersję na symulator, czyli wirtualny iPhone
na ekranie Maca, nie na prawdziwy telefon). Artifact `skarbonka-ios-simulator` w zakładce Actions
służy więc tylko do sprawdzenia, że nic się nie posypało — nie da się go zainstalować na telefonie.

### Jak zainstalować na prawdziwym iPhonie (wersja darmowa, 7 dni)

1. Na Macu zainstaluj **Xcode** za darmo z Mac App Store (duże pobieranie, ~15 GB, może chwilę zająć).
2. Otwórz Terminal, wejdź do folderu projektu: `cd ~/Downloads/skarbonka-native/ios` (popraw ścieżkę
   do miejsca, gdzie masz rozpakowany projekt).
3. Zainstaluj zależności: `npm install` a potem `npx cap sync ios`.
4. Wejdź do `cd App` i zainstaluj CocoaPods: `pod install` (jeśli brak polecenia `pod`,
   zainstaluj najpierw przez `sudo gem install cocoapods`).
5. Otwórz `App.xcworkspace` (nie `.xcodeproj`!) — dwuklik w Finderze, otworzy się Xcode.
6. W Xcode: kliknij projekt "App" w drzewku po lewej → zakładka "Signing & Capabilities" →
   w polu "Team" wybierz swoje Apple ID (jeśli go tam nie ma, kliknij "Add Account..." i zaloguj się
   zwykłym Apple ID — bez płacenia).
7. Podłącz iPhone kablem do Maca, na iPhonie zatwierdź "Zaufaj temu komputerowi".
8. U góry Xcode wybierz swój iPhone jako cel (zamiast symulatora) i kliknij ▶️ (Uruchom).
9. Na iPhonie: Ustawienia → Ogólne → VPN i zarządzanie urządzeniem → zaufaj swojemu Apple ID.

Appka będzie działać 7 dni. Po tym czasie wystarczy powtórzyć kroki 7-8 (kabel + Uruchom w Xcode).
