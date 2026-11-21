# Pokyny pro vývoj aplikace na sledování seriálů

## Projekt

Open-source Android aplikace pro sledování seriálů, správu zhlédnutých epizod, kalendář nadcházejících dílů a lokální push notifikace. Aplikace cílí na F-Droid, soukromí a reprodukovatelné buildy.

## Architektura

- `core-rust/` používá stable Rust. Veškerá byznys logika, síť, databáze a synchronizace patří sem. Veřejné rozhraní a datové typy exportuj přes UniFFI. SQLite používá `rusqlite` s `bundled`. Pro veřejné API TVmaze a WebDAV používej `reqwest` s `rustls-tls`. Použití OpenSSL je zakázané.
- `android-app/` je prezentační vrstva v Kotlinu a Jetpack Compose s Material 3. Pro periodické volání Rust funkce `check_todays_premieres` používej WorkManager a lokální Android notifikace. Přístup k poskytovatelům cloudového úložiště řeš pouze přes Android SAF (`ACTION_CREATE_DOCUMENT` a `ACTION_OPEN_DOCUMENT`).
- Zákaz Google Play Services, Firebase, proprietárních cloud SDK, telemetrie, trackerů, reklamních knihoven a binárních blobů v repozitáři. Závislosti sestavuj ze zdrojů. Notifikace mají fungovat lokálně bez vlastního backendu.

## Rozhraní a jazyk

- Všechny komponenty mají ostré hrany (`RectangleShape`, zaoblení `0.dp`). Místo elevace používej jemné kontrastní ohraničení `1.dp`. Paleta je zinková šedá a černá s oranžovým akcentem.
- Nabídni motivy System, Light, Dark a Amoled (`#000000`). Uživatelský HEX akcent má automaticky vypočítaný kontrast podle luminance. `ThemeSettings` patří do SQLite v Rustu.
- UI řetězce definuj v `res/values/strings.xml` a v příslušných jazykových variantách pro češtinu, esperanto, korejštinu, ukrajinštinu a klingonštinu. Značka Seriaro a názvy jazyků v jejich vlastním jazyce mají jedinou definici. Počty epizod a dní používají Android plurály podle pravidel jazyka. Jazyk lze přepnout v aplikaci. Názvy a popisy seriálů zobrazuj podle dat TVmaze. Údaje katalogu připisuj TVmaze pod CC BY-SA 4.0.

## Zálohy

- Rust generuje a parsuje verzované JSON zálohy (`schema_version: 1`) se sledovanými seriály, zhlédnutými epizodami, motivem a jazykem.
- Import umožní sloučení (`merge = true`) a úplné přepsání (`merge = false`). Přímý WebDAV upload a download řeší Rust. Přihlašovací údaje ukládej šifrovaně přes Android Keystore. Přihlašovací údaje nesmějí být v otevřené podobě v SQLite ani v JSON záloze.

## Testování

- Rust produkční kód nepoužívá `.unwrap()` ani `.expect()`. Používej typované chyby `thiserror` exportované přes UniFFI. Nové funkce jádra pokrývej unit testy. Před commitem spusť `cargo fmt` a `cargo clippy --all-targets -- -D warnings`.
- Compose používá jednosměrný tok dat a `ViewModel` se `StateFlow`. Síťové a databázové operace se spouštějí mimo hlavní vlákno na `Dispatchers.IO`.
- Ruční cross-build je možný pomocí `cargo ndk -t armeabi-v7a -t arm64-v8a -t x86_64 build --release`. Gradle běžně kompiluje nativní vrstvy a generuje Kotlin bindingy automaticky.
