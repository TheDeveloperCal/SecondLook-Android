# Contributing

Thanks for helping improve SecondLook. Keep checks local, explainable, and conservative; never upload submitted messages, URLs, looked-up numbers, or call contents. Public phone-number reports must stay clearly labeled as unverified and must not trigger automatic blocking.

## Before opening a pull request

1. Describe the behavior or defect and provide synthetic examples only.
2. Keep changes focused and preserve Room migrations and existing local data.
3. Run `./gradlew lint testDebugUnitTest assembleDebug compileDebugAndroidTestKotlin`.
4. If you change UI behavior, add or update Compose instrumentation coverage.
5. Do not include APKs, signing keys, credentials, personal data, or screenshots containing notifications or account details.

The project is GPL-3.0-only. By contributing, you agree to license your contributions under that license.
