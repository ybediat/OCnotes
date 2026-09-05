<div align="center">

# OCnotes

**Markdown notes on Android — local-only or synchronized with OpenCloud.**

[Version française](README.md)

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE.txt)
[![Go 1.26](https://img.shields.io/badge/Go-1.26-00ADD8?logo=go&logoColor=white)](go.mod)
[![Android 8+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#installation)
[![Status: alpha](https://img.shields.io/badge/status-alpha-orange.svg)](#project-status)
</div>

## What OCnotes does

OCnotes is an Android app for editing **Markdown** notes. Notes can remain on
the device without an account, or synchronize with an
[OpenCloud](https://opencloud.eu) server (a fork of ownCloud Infinite Scale).
When synchronized, they stay as ordinary `.md` files in the user's personal
space: readable from the web interface and usable by any other client.

- Markdown editor, formatting toolbar, and native Compose preview.
- Local-first operation: changes are stored locally first, then synchronized
  from a persistent queue when the network is available.
- Local-only mode: no server and no account are required.
- Folder tree navigation, creation, renaming, moving, and conflict detection
  through ETags.
- Create and edit `.md` and `.txt` files; view `.docx` and `.odt` documents in
  read-only mode.
- French, English, Spanish, and German interfaces.
- OpenCloud App Token authentication stored with Android Keystore-backed
  `EncryptedSharedPreferences` and never written to disk by the Go core.

## Screenshots

<img src="fastlane/metadata/android/fr-FR/images/phoneScreenshots/00-liste-notes.png" width="20%" alt="note list">
<img src="fastlane/metadata/android/fr-FR/images/phoneScreenshots/01-editeur-markdown.png" width="20%" alt="Markdown editor">
<img src="fastlane/metadata/android/fr-FR/images/phoneScreenshots/02-apercu-markdown.png" width="20%" alt="Markdown preview">

## Installation

Signed version **0.1.2** is available on the
[release page](https://github.com/ybediat/OCnotes/releases/tag/v0.1.2).
You can download and install
[`OCnotes-0.1.2.apk`](https://github.com/ybediat/OCnotes/releases/download/v0.1.2/OCnotes-0.1.2.apk)
directly on an Android device.

Android 8.0 (API 26) or later is required. To synchronize, configure an HTTPS
OpenCloud server and an App Token. The app also works fully in local-only mode.

## Building from source

Requirements:

- Go 1.26+
- JDK 17 and the Android SDK (API 35), including the NDK
- `gomobile` and `gobind` at the version pinned in `go.mod` (the Linux script
  installs them automatically)

On Linux, the build script regenerates the Go binding, runs Go and Android
tests, and creates an unsigned release APK:

```bash
bash scripts/build-android-linux.sh
```

The reference toolchain is Go 1.26.0, JDK 17, Gradle 8.9, Android platforms 26
and 35, and NDK 27.3.13750724. The script checks minimum compatible versions
rather than exact versions so third-party builders such as F-Droid can use
their own build image.

For a manual build:

```bash
go install golang.org/x/mobile/cmd/gomobile@v0.0.0-20260821190718-4776eadac327
go install golang.org/x/mobile/cmd/gobind@v0.0.0-20260821190718-4776eadac327
gomobile bind -target=android/arm64,android/amd64 -androidapi 26 -trimpath -ldflags="-s -w" -o android/app/libs/ocnotes.aar ./mobile
cd android && ./gradlew assembleDebug
```

`gomobile bind` requires `ANDROID_HOME` and `ANDROID_NDK_HOME`. Gradle does not
regenerate the `.aar`, so rerun `gomobile bind` whenever the `mobile/` API
changes.

## Tests

```bash
go test ./... -short
go vet ./... && gofmt -l .
cd android && ./gradlew testDebugUnitTest lintDebug
```

Integration tests use a real OpenCloud server and are skipped unless all three
variables are set:

```bash
export OCNOTES_IT_SERVER="https://cloud.example.org"
export OCNOTES_IT_USER="my-login"
export OCNOTES_IT_TOKEN="..."
go test ./... -run TestIntegration -v
```

## Project status

**Alpha.** Signed version **0.1.2** is available. The app works in local-only
mode and with OpenCloud synchronization, including offline changes.

The Go core and Android adapters have unit tests, and integration tests can run
against OpenCloud. The UI is manually tested on devices; there are no Android
instrumented tests yet.

Known limitations:

- Authentication uses App Tokens; OIDC is not supported yet.
- Raw HTML and `data:` images are not rendered in previews.
- Translations have not yet been reviewed by native speakers on device.

Planned priorities include additional languages, OIDC, and F-Droid publication.

## Documentation and contribution

Most development documentation, code, comments, tests, and issue discussions
are in French:

- [Technical documentation](docs/TECHNICAL.md)
- [Development guide](docs/DEVELOPMENT.md)
- [Testing guide](docs/TESTING.md)
- [Release guide](docs/RELEASING.md)
- [Contributing](CONTRIBUTING.md)
- [F-Droid submission notes](docs/fdroid/README.md)

## Security

See [SECURITY.md](SECURITY.md) to report a vulnerability.

## License

[MIT](LICENSE.txt).
