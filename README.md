# Seriaro

Seriaro is an Android app for tracking TV shows and watched episodes. It gets show and premiere information from the public TVmaze catalog. Your watchlist, viewing history, and settings are stored on your device. The app is licensed under GPL-3.0-or-later.

## Build

Gradle uses Temurin 27 as its build JVM. You can use JDK 21 to launch Gradle and run `sdkmanager`. The build also requires Rust 1.100.0 with the components listed in `rust-toolchain.toml`, plus the Android SDK. Use `sdkmanager` to install the required packages.

```sh
sdkmanager 'platforms;android-37.1' 'build-tools;36.0.0' 'ndk;28.2.13676358' platform-tools
export ANDROID_HOME=/path/to/android-sdk
cargo fmt --manifest-path core-rust/Cargo.toml --check
cargo test --manifest-path core-rust/Cargo.toml --locked
cargo clippy --manifest-path core-rust/Cargo.toml --locked --all-targets --all-features -- -D warnings
./android-app/gradlew :app:assembleDebug
```

The debug APK is written to `android-app/app/build/outputs/apk/debug/app-debug.apk`. Gradle generates the UniFFI Kotlin bindings and builds Rust for `armeabi-v7a`, `arm64-v8a`, and `x86_64`. The `gradlew` script downloads a pinned Gradle distribution and checks its SHA-256 digest. To use an existing Gradle 9.9.2 installation, set `SERIES_GRADLE` to its executable.

## Usage

- Open **Search** to find a TV show and add it to your watchlist. The public TVmaze API requires neither an account nor an API key. Show titles and descriptions come from the catalog. In Settings, you can switch the app language to Czech, English, Esperanto, Korean, Ukrainian, or Klingon. The Klingon interface uses Latin script and ISO date formatting.
- After upgrading from version 0.1.0, open a previously saved show and select **Link to TVmaze**. Choose the matching show and confirm the transfer of your viewing history by season and episode number. You can still import older JSON v1 backups.
- Catalog data comes from TVmaze under CC BY-SA 4.0. The app converts HTML descriptions to plain text. [TVmaze](https://www.tvmaze.com/) · [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/)
- On a show's details screen, you can mark individual episodes, a selected season, or all loaded episodes as watched, including upcoming episodes. You can archive a completed show and restore it to your active watchlist at any time. Under **Following**, you can combine the status filter with a title search.
- You can reorder the top bar buttons in **Settings**. The calendar displays dates from TVmaze, and WorkManager checks for premieres about every six hours as Android scheduling allows. You can turn local alerts on or off under **Settings → Premiere notifications**. Android notification permission is also required.
- You can save and open JSON v1 backups through the Android file picker. For WebDAV, enter the full HTTPS URL of the backup file, such as `https://cloud.example/remote.php/dav/files/jan/series.json`, your username, and your password or app password. The server must present a trusted TLS certificate. The app encrypts your password using Android Keystore.
- Merging adds missing shows and episodes, combines watched episodes, and preserves your local settings. Overwriting restores the contents and settings from the backup. The JSON includes a copy of the show data so you can read it offline after restoring.

## F-Droid build status

Cargo builds Rust dependencies from source using `Cargo.lock`. The Android build uses published AndroidX, Kotlin, and JNA packages from Maven repositories. A separate build is still needed to verify that these dependencies can be built from source and that the release APK is reproducible. The source tree contains no bundled binary libraries or Gradle wrapper JAR.

### F-Droid on GitHub Pages

The F-Droid repository is hosted on GitHub Pages and deployed through GitHub Actions. [Seriaro F-Droid repository](https://bezverec.github.io/seriaro/fdroid/repo)

Place the signed APK with application ID `org.seriaro.android` in `keys/repo/`. In the `keys` directory, generate a signed index and package the public files.

```sh
fdroid update
tar --hard-dereference -czf fdroid-pages.tar.gz --transform='s,^repo,fdroid/repo,' repo
```

Attach `fdroid-pages.tar.gz` to a published GitHub release. Run the **Publish F-Droid repository** workflow with that release's tag to deploy the repository to GitHub Pages. Keep the signing keys and password-bearing configuration in your local `keys` directory.

Android treats `org.seriaro.android` as a separate app. To transfer your watchlist, viewing history, and settings, export a JSON backup from the previous app and import it into the new one. Enter your WebDAV credentials again.
