# Demande d'inclusion F-Droid — texte prêt à coller

Le corps ci-dessous est en anglais : c'est la langue du tracker
<https://gitlab.com/fdroid/rfp/-/issues>. Le reste du projet reste en français.

Joindre [`eu.opennote.yml`](eu.opennote.yml) à l'issue, ou coller son contenu
dans un bloc de code.

---

**Titre de l'issue** : `OpenNote — offline-first Markdown notes for OpenCloud servers`

---

### Package/Application ID

`eu.opennote`

### Name

OpenNote

### Description

OpenNote is a Markdown note editor for [OpenCloud](https://opencloud.eu)
servers (a fork of ownCloud Infinite Scale).

Notes stay as plain `.md` files in the user's personal OpenCloud space, so they
remain readable from the web interface, syncable with any other client, and
accessible without the app.

- Create, edit, rename, move and organise Markdown notes and folders.
- Works offline: reads and writes go to a local cache and a persisted queue,
  and sync resumes when the network returns.
- Native Markdown preview and formatting toolbar.
- ETag-based conflict detection, so a note edited on both sides is never
  silently overwritten.
- Authentication by OpenCloud App Token, stored with the Android Keystore.

The server is configured by the user. There is no vendor backend, no account
system, no telemetry.

### License

MIT — `LICENSE.txt` at the repository root.

### Source Code

<https://github.com/ybediat/OpenNote>

### Issue Tracker

<https://github.com/ybediat/OpenNote/issues>

### Changelog

<https://github.com/ybediat/OpenNote/releases>

### Categories

Writing, Internet

### Metadata

A proposed `metadata/eu.opennote.yml` is attached, kept in the repository at
`docs/fdroid/eu.opennote.yml` so it stays in sync with the source. The points
worth reviewing are below.

**The core is written in Go**, bound to the Kotlin/Compose UI with
`gomobile bind`. The recipe therefore:

- installs Go at the exact version pinned in `go.mod` (1.26.0) in `sudo:`,
  with the official SHA-256 checked;
- runs `gomobile bind` in `prebuild:`, producing
  `android/app/libs/opennote.aar` before Gradle starts;
- needs `scanignore: android/app/libs/opennote.aar` — that AAR is a binary
  inside the build tree, but it is produced from the repository's own sources
  by the preceding step. `GOPATH` is left at its default (`$HOME/go`), outside
  the build tree, so the `gomobile` and `gobind` executables are never scanned.

**No prebuilt binaries are committed.** The only binary in the repository is
the Gradle wrapper JAR, validated against the official Gradle distribution
registry by CI on every run
(`gradle/actions/wrapper-validation`).

**Dependencies** are AndroidX, Kotlin, kotlinx.serialization, WorkManager and
[goldmark](https://github.com/yuin/goldmark) (MIT). No Google Play Services, no
Firebase, no analytics, no ad SDK, no crash reporter.

**Permissions**: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` —
the last one for sync-conflict notifications.

**ABIs**: `arm64-v8a` and `x86_64`. `armeabi-v7a` is omitted only because it
cannot be tested — ARM 32-bit system images stop at API 25 and `minSdk` is 26.
The Go core is 32-bit clean (no `sync/atomic`, no `unsafe`, no cgo, all sizes
`int64`; `GOARCH=386 go test ./...` passes), so it can be added if there is
demand from users with such devices.

**Fastlane metadata** is in the repository under `fastlane/metadata/android/`
for `en-US` and `fr-FR`, including a 512×512 icon, screenshots and per-
`versionCode` changelogs.

### Signing

I would like to use **reproducible builds with the upstream signature**, so
that a user can move between an F-Droid install, a direct APK download and
Obtainium without uninstalling. The recipe sets:

```yaml
Binaries: https://github.com/ybediat/OpenNote/releases/download/V%v/OpenNote-%v.apk
AllowedAPKSigningKeys: 9eda46c9fdb2756cdad00a548c27261d1a5bfb25b06e11892071bbd19f35e6ec
```

The published APK is the one built by the project's Linux CI from the tag, then
signed offline on the machine holding the key. To be transparent: **I have not
yet verified that it reproduces on your buildserver.** I have only verified
that a locally built (Windows) APK does *not* match the Linux CI build, and
changed the release tooling so the CI artifact is what gets signed by default.

If the first rebuild does not match, I would rather iterate on that than have
the app blocked — and I am happy to switch to F-Droid signing for the initial
inclusion if you prefer that order.

### Notes

- Current release: `V0.1.2` (versionCode 3).
- The app is **alpha**. It works day to day, but the UI has no instrumented
  tests, and the Spanish and German translations have not been reviewed by
  native speakers on device.
- The project is developed in French; code, comments and documentation are in
  French. The app itself ships French, English, Spanish and German.
