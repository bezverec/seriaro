# Seriaro

Android aplikace pro sledování seriálů a zhlédnutých epizod. Údaje o seriálech a premiérách čte z veřejného katalogu TVmaze. Seznam, historie a uživatelská nastavení jsou místní. Licence GPL-3.0-or-later.

## Sestavení

Potřebujete JDK 17 nebo 21, Rust 1.100.0 s komponentami z `rust-toolchain.toml` a Android SDK. Příkaz `sdkmanager` nainstaluje potřebné balíky:

```sh
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;28.2.13676358' platform-tools
export ANDROID_HOME=/cesta/k/android-sdk
cargo fmt --manifest-path core-rust/Cargo.toml --check
cargo test --manifest-path core-rust/Cargo.toml --locked
cargo clippy --manifest-path core-rust/Cargo.toml --locked --all-targets --all-features -- -D warnings
./android-app/gradlew :app:assembleDebug
```

Výstup je `android-app/app/build/outputs/apk/debug/app-debug.apk`. Gradle během sestavení vygeneruje Kotlin bindingy UniFFI a sestaví Rust pro `armeabi-v7a`, `arm64-v8a` a `x86_64`. Skript `gradlew` stahuje přesně určenou distribuci Gradle a ověřuje její SHA-256. Lze také použít nainstalované Gradle 8.11.1 nastavením `SERIES_GRADLE` na jeho spustitelný soubor.

## Použití

- V **Hledat** vyhledejte seriál a přidejte ho ke sledovaným. Veřejné API TVmaze funguje bez účtu a klíče. Názvy a popisy odpovídají katalogu. Rozhraní lze v nastavení přepnout na češtinu, angličtinu, esperanto, korejštinu, ukrajinštinu nebo klingonštinu. Klingonština používá latinkový zápis a datum ve formátu ISO.
- Po aktualizaci z verze 0.1.0 můžete v detailu dříve uloženého seriálu použít **Propojit s TVmaze**. Zvolte odpovídající titul a potvrďte přesun historie podle řady a čísla epizody. Starší zálohy JSON v1 lze dál importovat.
- Údaje dodává [TVmaze](https://www.tvmaze.com/) pod licencí [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/). Popisy se převádějí z HTML do prostého textu.
- V detailu seriálu můžete jako zhlédnuté označit jednotlivé epizody, vybranou řadu nebo všechny načtené díly včetně nadcházejících. Dokončený seriál lze archivovat a kdykoli vrátit mezi aktivní. Ve **Sledovaných** lze kombinovat filtr podle stavu s hledáním v názvech.
- Pořadí tlačítek v horní liště změníte v **Nastavení**. Kalendář zobrazuje termíny z TVmaze a WorkManager kontroluje premiéry zhruba každých šest hodin podle možností Androidu. Místní upozornění můžete zapnout nebo vypnout v **Nastavení → Upozornění na premiéry**. Pro příjem upozornění je potřeba také povolení v Androidu.
- Zálohu JSON v1 lze uložit nebo otevřít v systémovém výběru souborů. Pro WebDAV zadejte úplnou HTTPS adresu souboru například `https://cloud.example/remote.php/dav/files/jan/series.json`, uživatele a heslo nebo aplikační heslo. Server musí předložit důvěryhodný TLS certifikát. Heslo je šifrované pomocí Android Keystore.
- Sloučení přidává chybějící seriály a epizody, sjednotí seznam zhlédnutých epizod a zachová místní nastavení. Přepsání obnovuje obsah a nastavení ze zálohy. JSON obsahuje kopii údajů o seriálech pro čtení po obnově bez připojení.

## Stav pro F-Droid

Cargo sestavuje knihovny Rustu ze zdrojů podle `Cargo.lock`. Android build používá vydané balíky AndroidX, Kotlin a JNA z repozitářů Maven. Striktní požadavek na sestavení těchto závislostí ze zdrojového kódu a reprodukovatelnost release APK je potřeba ještě ověřit v samostatném sestavení. Ve zdrojovém stromu nejsou vložené binární knihovny ani Gradle wrapper JAR.
