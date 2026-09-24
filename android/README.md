# YANA/ for Android

The Android client: Kotlin, Jetpack Compose, Material 3. It signs in to a
YANA/ server and browses its spaces, folders and notes. HTML notes
render in a sandboxed WebView and edit by source; markdown notes are
read-only until the editor arrives.

## Build

```sh
cd android
./gradlew build              # lint, unit tests, debug and release builds
./gradlew assembleDebug      # just the debug APK
./gradlew installDebug       # onto a running emulator or a connected phone
./gradlew connectedDebugAndroidTest  # the WebView sandbox test, on a device
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk` and
installs beside a release build (`com.collinpendleton.yana.debug`). CI
runs `./gradlew build` on every pull request that touches `android/` and
attaches the debug APK to the run as `yana-debug-apk`.

Put the SDK location in `android/local.properties`
(`sdk.dir=/path/to/Android/sdk`) or set `ANDROID_HOME`. Android Studio
writes the file itself.

| Piece | Version | Why |
|---|---|---|
| JDK | 17 | What AGP 9 needs; the same JDK the CRDT AAR builds with |
| Gradle | 9.8.0 (wrapper, checksum pinned) | |
| Android Gradle Plugin | 9.4.1 | Compiles Kotlin itself, so there is no separate Kotlin Android plugin |
| Kotlin | 2.4.20 (Compose and serialization compiler plugins) | |
| `compileSdk` | 37 | Current AndroidX and OkHttp releases require it to compile against |
| `targetSdk` | 36 (Android 16) | The newest release the app has been run against; it moves up after a run on the next release's behaviour changes |
| `minSdk` | 26 (Android 8.0) | Adaptive icons and `java.time` without desugaring, the Keystore features `EncryptedSharedPreferences` relies on, and about 97% of active devices. The CRDT AAR's floor is 24, so it does not constrain this |

## Point it at a local server

Run `yana` on your computer, then enter its address on the first
screen:

- **Emulator:** `http://10.0.2.2:8080`. The emulator reaches the host's
  loopback at 10.0.2.2, so a server on `127.0.0.1` is enough.
- **Phone on the same network:** `http://<your computer's LAN IP>:8080`.
  The server must listen on every interface (the default `:8080` does;
  `YANA_LISTEN=127.0.0.1:8080` does not).

A bare host name becomes `https://`. Plain HTTP works because a home
server often has no certificate; the app says when an address uses it.
User-installed CA certificates are trusted, so a server behind a private
CA works once its root is installed on the phone.

A fresh server with no accounts shows the first-run form, which creates
the owner, the same as the web's first visit.

## Sign-in and sessions

Sign-in sends the password once and gets back an access token (15
minutes) and a refresh token (30 days of inactivity). Both, with the
server address and the account, live in `EncryptedSharedPreferences`
under an AES-256 Keystore key, and are left out of backups: the key does
not travel, so a restored copy could not be opened anyway.

Every request carries the access token. One that is about to lapse is
swapped for a new one before the request goes out; a request refused
with `401` trades the refresh token once and retries. The app keeps its
session across restarts without asking for the password again.

The session is labeled with the device (`android · Pixel 8`), so it
appears by name in the session list on the web's Account page. Revoking
it there signs the phone out on its next request: the refresh is
refused, the stored tokens are cleared, and the app returns to sign-in
saying the device was signed out. Sign out in the app's settings revokes
the session on the server first, then forgets it locally, even when the
server cannot be reached.

## The REST client

Retrofit over OkHttp, with kotlinx.serialization for JSON. The token
lifecycle sits in one OkHttp interceptor and one `Authenticator`
(`data/TokenAuth.kt`), which is the whole of silent refresh. The same
OkHttp client carries the realtime WebSocket in the sync phase, so
there is one HTTP stack, one connection pool and one TLS configuration.
Ktor would need an engine plus its auth and content-negotiation plugins
for the same result.

## HTML notes

HTML notes render in a WebView on the content origin, the server's
second listener, with the same boundary the web's iframe has:
JavaScript runs, but there is no bridge to the app, no file access, and
mixed content is blocked. The signed view URL is the only credential
the WebView holds, minted fresh on every open and expiring in five
minutes. Navigation stays on the content origin; other links open in
the system browser. The source edits in a plain text screen with
explicit saves — whole-file, last-write-wins, with the server parking
the diverged version as a conflict copy it names in the save's
message. Trust shows as a read-only badge; it changes on the web. An
instrumented test (`NoteWebViewSandboxTest`) runs a hostile note on a
device and checks that its script cannot fetch the API, read the app
origin's cookies, or navigate the WebView off the content origin, and
that a trusted note's canvas animation runs.

## Layout

```
app/src/main/java/com/collinpendleton/yana/
  YanaApp.kt, MainActivity.kt   the app's single client and preferences
  data/                          API models, Retrofit interfaces, token refresh, session store
  ui/Nav.kt                      routes: server → sign-in → spaces → space tree → note; settings
  ui/screens/                    one file per screen
  ui/htmlnote/                   the sandboxed WebView, the source editor, view-token minting
  ui/theme/                      the Identity palette and type
crdt/                            the CRDT engine slot (below)
fonts/                           licenses for the bundled fonts
```

## The CRDT engine

`crdt/` is where the AAR that `make android-crdt` builds from
`mobile/crdt` lands (`crdt/libs/yana-crdt.aar`; see
[mobile/crdt/README.md](../mobile/crdt/README.md)). The AAR is a build
artifact and not committed. The `:crdt` project publishes it as its
only artifact, so the app's `implementation(project(":crdt"))` picks up
its classes and native libraries when it is there and nothing when it is
not. Nothing calls into it until the editor, so the app builds and runs
either way.

## Identity

The UI face is Source Sans 3, a humanist sans. Mono (Source Code Pro)
is used only for the `YANA/` wordmark and code. Colours are the web
client's tokens: warm off-white, near-black ink, one amber accent, with
light and dark variants. Settings can follow the system or pin one. The
launcher icon is the slash from the wordmark. Both fonts are under the
SIL Open Font License; the texts are in `fonts/`.
