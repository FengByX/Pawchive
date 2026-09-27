<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Pawchive" width="120" />
</p>

<h1 align="center">Pawchive</h1>

<p align="center">
  <a href="README.md">中文</a> | <a href="README.ja.md">日本語</a>
</p>

<p align="center">
  A polished, fluid third-party Android client for the full <a href="https://pawchive.pw">Pawchive</a> experience.<br/>
  Aggregates creator content from Patreon, Fanbox, Discord and more — browse, search, bookmark, and enjoy immersive media playback.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.3.20-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/API-30%2B-34A853?style=for-the-badge&logo=android&logoColor=white" alt="Min API" />
  <img src="https://img.shields.io/badge/Target_API-36-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Target API" />
  <img src="https://img.shields.io/github/v/release/FengByX/Pawchive?style=for-the-badge&logo=android&label=Release&color=blue" alt="Release" />
  <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="License" />
</p>

<p align="center">
  <a href="https://github.com/FengByX/Pawchive/releases">
    <img src="https://img.shields.io/badge/Download-Releases-181717?style=for-the-badge&logo=github&logoColor=white" alt="Download" />
  </a>
  <a href="https://t.me/PawchiveX">
    <img src="https://img.shields.io/badge/Telegram-PawchiveX-229ED9?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram Channel" />
  </a>
</p>

---

## Features

### Content Browsing
- **Home feed**: Paginated latest posts with keyword filters; optional "one post per creator" and "hide bookmarked creators"
- **Creator profiles**: Posts, announcements, fan cards, and linked accounts
- **Post details**: Full HTML-rendered content (whitelisted tags), comments, revision history, file downloads
- **Multi-platform**: Aggregates Patreon, Fanbox, Discord with brand-colored tags

### Smart Search
- **Keyword search**: Searches posts and creators simultaneously with tabbed results
- **File hash lookup**: Trace source material by file hash, including Discord results
- **Offline full-text search**: Room FTS4 index with CJK bigram tokenization — search bookmarks without network
- **Search history**: Locally persisted, configurable retention (5–50), single-item delete and clear-all

### Immersive Media
- **Image viewer**: Pinch-to-zoom, double-tap zoom, boundary-constrained panning
- **Animated images**: GIF / animated WebP / animated HEIF, flagged with a "GIF" badge in lists
- **Video player**: Media3 ExoPlayer with Bilibili-style controls, playback speed, resume from last position, picture-in-picture; fullscreen reuses the inline player instance — zero re-buffering on enter/exit, aspect-ratio-preserving scaling
- **Graceful fallback**: Original image first, automatic fallback to thumbnail when missing

### Bookmarks & Accounts
- **Multi-account switching** with isolated bookmarks / history / downloads
- **Cloud bookmarks**: Sync bookmarked posts and creators when logged in, with immediate cache invalidation on write
- **Local bookmarks**: No login required; bookmarking builds an offline archive index
- **Offline reading**: Full-text archive of bookmarked posts (Room FTS4), searchable and readable offline

### Content Subscriptions & Notifications
- **Creator subscriptions**: Periodic new-post detection for followed creators (interval configurable, 15 min minimum); optional "bookmark implies subscribe"
- **System notifications**: New posts are pushed instantly and can be turned off anytime
- **In-app notification center**: Reachable from the home bell icon with a live unread badge; mark items read individually or all at once, and unsubscribe anytime from the subscription manager

### Download Center
- **HTTP Range resumable download**: Temp file kept on pause, resumed via Range request; falls back to a full download when the server ignores Range
- **Per-task notifications**: One notification per task with percentage, downloaded size, speed (EMA-smoothed) and ETA; pause / resume / cancel actions inline
- **Background downloads**: Kept alive by a `dataSync` foreground service
- **Download rules**: Auto-enqueue by creator / service / file type
- **Server-friendly limits**: Concurrency capped at 5, minimum 1s interval between retries of the same file, identifiable custom User-Agent

### Personalization
- **Languages**: 中文 / English / 日本語, switch instantly
- **Appearance**: Light / Dark / System, six accent themes, Material Design 3
- **Display & scaling**: Continuous sliders for global UI scale and font size, live preview and immediate effect
- **Data saving**: Lists load thumbnails by default; switchable to originals
- **Download parameters**: Custom directory (SAF), max concurrency (1–5), retry count (1–3), Wi-Fi-only, filename format
- **Auto backup**: Daily backup export to a chosen directory
- **In-app updates**: Auto-check GitHub Releases with semantic version comparison, STABLE / BETA channels, and "ignore this version"

---

## Tech Stack

| Category | Technology | Notes |
|----------|-----------|-------|
| **Language** | Kotlin 2.3.20 | Bundled with AGP 9.3.2; no separate Kotlin plugin needed |
| **Min SDK** | API 30 (Android 11) | Covers the vast majority of active devices |
| **Target / Compile SDK** | API 36 (minorApiLevel 1) | Latest Android version |
| **UI** | XML + ViewBinding | Declarative layouts, type-safe access |
| **Design** | Material Design 3 | Card groups, segmented buttons, brand tags |
| **Modularization** | 10 Gradle modules | `app` / 7×`feature-*` / `data` / `core` |
| **DI** | Hilt 2.59.2 + KSP 2.3.6 | `@HiltAndroidApp` / `@AndroidEntryPoint` |
| **Storage** | Room 2.8.4 + DataStore 1.1.1 | Room for download history and offline archive, DataStore for settings |
| **Download** | Custom OkHttp streaming | Replaced okdownload in 1.7.0; single connection + HTTP Range resume |
| **Network** | Retrofit 2.9.0 + OkHttp 4.12.0 | Type-safe HTTP client |
| **Images** | Coil 2.6.0 + coil-gif | Coroutine-native; `ImageDecoderDecoder` registered for animated images |
| **Video** | AndroidX Media3 1.4.1 | ExoPlayer + OkHttp data source |
| **Background work** | WorkManager 2.9.0 + Hilt | Periodic subscription sync, auto backup, cache cleanup |
| **Build** | Gradle 9.5.0 + AGP 9.3.2 | Version catalog (`libs.versions.toml`) as single source of truth |
| **Quality gate** | Kover 0.9.9 | Core layer (core + data) line coverage ≥ 45% |

---

## Key Technical Highlights

### 1. Automatic Cloudflare Challenge Bypass

The target site uses Cloudflare protection, so plain OkHttp requests get a 403. `CloudflareManager` runs the JS challenge in a hidden WebView and extracts the `cf_clearance` cookie, which it injects into all subsequent OkHttp requests **together with the User-Agent it is bound to**:

- **Single-flight**: Concurrent callers share one `CompletableDeferred`, so multiple WebViews never start in parallel
- **Persisted credentials**: Stored in EncryptedSharedPreferences with a 25-minute TTL, so a cold start within that window skips the challenge
- **`session` stripping**: Anonymous session cookies written by the WebView would otherwise stack on the real login session and cause spurious 401s, so they are stripped before injection
- **Hardening**: WebView construction and configuration degrade gracefully (a third-party WebView throwing an `Error` does not crash the app), and `onRenderProcessGone` is overridden so a renderer crash cannot take down the process

### 2. Direct OkHttp Streaming Download Engine

okdownload was removed in 1.7.0 — source-level forensics showed a `LockSupport.park()` with no timeout on its sync thread, multi-block downloads issuing concurrent Range requests against the server's rate-limit policy, and opaque errors when a response was truncated. It is now a single-connection streaming downloader:

- **Pause / resume**: The temp file is kept on pause and resumed with `Range: bytes=N-`; a server that ignores Range and returns 200 silently degrades to a full download
- **Truncation detection**: Compares `Content-Length` against bytes actually read, failing fast into a backoff retry
- **Interruptible**: The read loop checks coroutine liveness every 64KB, so cancellation takes effect immediately in both the download and write-out phases
- **Temp-file isolation**: Data lands in a temp file first and is copied to the target stream only on success, so a retry never contaminates already-written bytes
- **Four separate clients**: The download client sets no `callTimeout` (otherwise any file taking over 60 seconds would be killed by the watchdog) and mounts no memory-cache interceptor

### 3. Smart Interceptor Chain

- **Scoped injection**: Cookie, Referer and User-Agent are injected only for the main domain `pawchive.pw`; CDN subdomains get the User-Agent only (avoiding hotlink protection and credential leakage)
- **403 fallback**: `ClearanceRetryInterceptor` forces a clearance refresh and retries once on 403
- **Sanitized logging**: `Authorization` / `Cookie` / `Set-Cookie` are always masked, and logging is off in release builds
- **Per-account cache**: GET JSON responses are cached for 5 minutes under a session-hash namespace (no cross-account reuse), with precise invalidation by path

### 4. Single Activity + Modular Navigation

- Bottom navigation and **ViewPager2** stay in two-way sync; main tabs follow finger swipes
- `offscreenPageLimit = 1` keeps the home and bookmark pages from preloading simultaneously and racing for the same clearance task
- **AppNavigator interface**: Features navigate through an interface, with zero direct inter-module dependencies

### 5. Offline Archive and Full-Text Search

- Bookmarking writes both a Room entity row and an FTS4 shadow row inside one transaction, keeping the two consistent
- **CJK bigram tokenization** makes Chinese searchable without word separators
- **Weighted relevance**: title > creator > content/attachments, queried per layer and merged in weight order with deduplication

### 6. Performance and Polish

- **Skeleton loading**: Custom `SkeletonHelper` shimmer pulse animation, globally disableable via "reduce animations"
- **Non-blocking startup**: Language, appearance and scaling read from a lightweight SharedPreferences startup cache instead of DataStore
- **In-memory snapshots with async persistence**: Settings and bookmarks read from memory and write asynchronously, so the UI responds instantly

---

## Project Structure

```
Pawchive/
├── app/                    # Assembly: Application / MainActivity / main pager
├── feature-common/         # Shared UI: SkeletonHelper / ZoomableImageView / common adapters / AppNavigator
├── feature-home/           # Home feed
├── feature-search/         # Search (online + offline full-text)
├── feature-post/           # Post details / image viewer / video playback
├── feature-downloads/      # Download center
├── feature-settings/       # Settings (download rules, subscriptions, backup, cache management)
├── feature-account/        # Account / login / bookmarks
├── data/                   # Business layer: Repository / download engine / Worker / GitHub update check
├── core/                   # Infrastructure: network / models / Room / DataStore / utilities
└── gradle/libs.versions.toml   # Version catalog (single source of dependencies)
```

**Dependency direction**: `:app` → `:feature-*` → `:data` → `:core`

---

## Getting Started

### Requirements
- **Android Studio** Meerkat (2024.3+) or higher
- **JDK** 17+ (CI uses 21)
- **Gradle** 9.5.0 (wrapper included, with SHA-256 verification)

### Clone & Build

```bash
git clone https://github.com/FengByX/Pawchive.git
cd Pawchive
./gradlew assembleRelease
```

> APK output: `app/build/outputs/apk/release/Pawchive-v<version>.apk`
> (the file name comes from `VERSION_NAME` in `gradle.properties`, the single source of the version; the build fails if it is missing or invalid)

### Common Tasks

```bash
./gradlew testDebugUnitTest                       # Unit tests
./gradlew koverVerify koverHtmlReport             # Coverage gate and report
./gradlew lintDebug                               # Lint
```

### Install

Download the latest APK from [Releases](https://github.com/FengByX/Pawchive/releases) and install on an Android 11+ device.

---

## Permissions

| Permission | Purpose |
|-----------|---------|
| `INTERNET` | Network requests |
| `ACCESS_NETWORK_STATE` | Network status detection (Wi-Fi-only downloads, connectivity checks) |
| `POST_NOTIFICATIONS` | Download progress and content update notifications (runtime request on Android 13+; downloads and browsing unaffected if denied) |
| `FOREGROUND_SERVICE` | Download foreground service |
| `FOREGROUND_SERVICE_DATA_SYNC` | Foreground service type (required on Android 14+ so background downloads survive) |
| `WRITE_EXTERNAL_STORAGE` | Declared for API ≤ 28 only; newer versions use MediaStore |
| `ACCESS_MEDIA_LOCATION` | Declared for API ≤ 32 only; reads media location metadata |

---

## Engineering Quality

| Mechanism | Description |
|-----------|-------------|
| **CI pipeline** | Four job groups: build (debug + R8 release), unit tests, lint, coverage gate, plus dependency review |
| **Coverage ratchet** | Core layer (core + data) line coverage floor of 45%, monotonic; CI blocks any regression |
| **R8 mapping archive** | Release builds enable obfuscation and resource shrinking; `mapping.txt` is archived by CI for 90 days so field crash stacks can be deobfuscated |
| **Dependency review** | PRs are blocked on dependencies with high or critical vulnerabilities |
| **Crash diagnostics** | A global `CrashHandler` writes crash logs to disk, shareable via FileProvider |
| **Locale parity** | Chinese / English / Japanese string resources are key-aligned in full |

---

## Support & Security

| Document | Content |
|----------|---------|
| [SUPPORT.md](SUPPORT.md) | Support window, release cadence, requirements, known limits, EOL policy |
| [SECURITY.md](SECURITY.md) | Vulnerability reporting channel and response times |
| [CHANGELOG.md](CHANGELOG.md) | Version history |
| [NOTICE.md](NOTICE.md) | Third-party components and licenses |

---

## Contributing

Issues and Pull Requests are welcome. Before submitting:

1. Keep code style consistent with existing code
2. Add string resources for all three languages (`values/`, `values-en/`, `values-ja/` keys must stay aligned)
3. Follow Material Design 3 guidelines
4. Accompany new core-layer logic with unit tests so coverage does not fall below the gate

---

## License

This project is open source under the **MIT License**.

---

<p align="center">
  <sub>Icons from <a href="https://lucide.dev">Lucide</a> · Inspired by Material Design 3</sub>
</p>
