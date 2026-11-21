# Contributing

Thanks for helping out!

- **Bugs / ideas:** open an issue using the templates. Never post playlist links, logins, tokens or license keys.
- **Code:** fork, create a branch, and open a pull request against `main`. GitHub Actions builds the APK and runs the tests on every PR.
- **Build locally:** `./gradlew assembleDebug` (JDK 17 + Android SDK, or just open the folder in Android Studio).
- **Tests:** `./gradlew testDebugUnitTest` – please add a test when you touch the playlist, guide, or stream parsers in `app/src/main/java/.../data/`.
- **Style:** Kotlin official style (Android Studio's default formatter). Keep the UI usable with only a TV remote **and** with touch/mouse.
- **Content:** this project is a player only. PRs that add channels, playlists or providers won't be accepted.

By contributing you agree that your contributions are licensed under the [GPL-3.0](LICENSE).
