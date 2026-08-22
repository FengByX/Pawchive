<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Pawchive" width="120" />
</p>

<h1 align="center">Pawchive</h1>

<p align="center">
  <a href="README.md">中文</a> | <a href="README.en.md">English</a>
</p>

<p align="center">
  <a href="https://pawchive.pw">Pawchive</a> プラットフォームの完全な体験をもたらす、洗練されたサードパーティ製 Android クライアント。<br/>
  Patreon、Fanbox、Discord などのクリエイターコンテンツを集約し、閲覧・検索・ブックマーク・没入型メディア再生に対応。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.2.10-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/API-30%2B-34A853?style=for-the-badge&logo=android&logoColor=white" alt="Min API" />
  <img src="https://img.shields.io/badge/Target_API-36-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Target API" />
  <img src="https://img.shields.io/badge/Release-v1.6.6-blue?style=for-the-badge&logo=android" alt="Release" />
  <img src="https://img.shields.io/badge/License-MIT-green?style=for-the-badge" alt="License" />
</p>

<p align="center">
  <a href="https://github.com/FengByX/Pawchive/releases">
    <img src="https://img.shields.io/badge/ダウンロード-Releases-181717?style=for-the-badge&logo=github&logoColor=white" alt="Download" />
  </a>
  <a href="https://t.me/PawchiveX">
    <img src="https://img.shields.io/badge/Telegram-PawchiveX-229ED9?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram Channel" />
  </a>
</p>

---

## 機能

### コンテンツ閲覧
- **ホームフィード**：最新コンテンツのページネーション読み込み、キーワードフィルタと複数のソートに対応
- **クリエイタープロフィール**：投稿、お知らせ、ファンカード、関連アカウントを表示
- **投稿詳細**：完全な本文、コメントツリー、改訂履歴、ファイルダウンロード
- **マルチプラットフォーム**：Patreon、Fanbox、Discord などを集約、プラットフォームタグはブランドカラー

### スマート検索
- **キーワード検索**：投稿とクリエイターを同時に検索、タブで結果を切り替え
- **ファイルハッシュ検索**：ファイルハッシュで素材の出典を追跡、Discord の結果にも対応
- **検索履歴**：ローカルに永続化、個別削除と全消去に対応

### 没入型メディア
- **HD 画像ビューア**：ピンチズーム、ダブルタップズーム、自由ドラッグ
- **動画再生**：Media3 ExoPlayer ベース、Bilibili 風コントロール、再生速度、フルスクリーン、前回位置から再開
- **マルチドメインフォールバック**：サムネイル → 原画 → ダウンロード CDN の 3 段階自動切り替え

### ブックマークとアカウント
- マルチアカウント切り替え、ブックマーク/履歴/ダウンロードを個別に分離
- ブックマークのオフラインアーカイブ（Room FTS4）：ネットワークなしでも検索と閲覧が可能
- **クラウドブックマーク**：ログイン時に保存した投稿とクリエイターを同期
- **ローカルブックマーク**：ログインなしでもローカルで管理可能

### ダウンロードセンター
- **okdownload レジュームエンジン**：ネットワーク切断後にブレークポイントから自動再開
- **進捗通知**：通知バーにリアルタイムでダウンロード進捗を表示
- **バックグラウンド継続**：アプリがバックグラウンドに入ってもダウンロードを継続
- **ダウンロードルール**：クリエイター / サービス / ファイル種別ごとに自動ダウンロードルールを設定
- **履歴管理**：キャンセル、再試行、ダウンロード記録の消去に対応

### カスタマイズ
- **多言語**：中文 / English / 日本語、即時切り替え
- **外観**：ライト / ダーク / システム従属、Material Design 3 テーマ
- **ダウンロード管理**：カスタムダウンロードディレクトリ（SAF）、キャッシュ確認とクリーンアップ
- **コンテンツ更新通知**：登録クリエイターの新着投稿を定期的に確認
- **アプリ内更新**：GitHub Release を自動確認、セマンティックバージョン比較

---

## 技術スタック

| カテゴリ | 技術 | 備考 |
|----------|------|------|
| **言語** | Kotlin 2.2.10 | モダンでヌルセーフな JVM 言語 |
| **最小 SDK** | API 30 (Android 11) | アクティブ端末の 95%+ をカバー |
| **ターゲット SDK** | API 36 | 最新 Android バージョン |
| **UI** | XML + ViewBinding | 宣言的レイアウト、型安全アクセス |
| **デザイン** | Material Design 3 | カードグループ、セグメントボタン |
| **モジュール化** | マルチ Gradle モジュール | `app` / `feature-*` / `data` / `core` |
| **DI** | Hilt + KSP | `@HiltAndroidApp` / `@AndroidEntryPoint` |
| **ストレージ** | Room + DataStore | 履歴/アーカイブは Room、設定は DataStore |
| **ダウンロード** | okdownload 1.0.7 | レジューム、進捗コールバック、OkHttp 連携 |
| **ネットワーク** | Retrofit + OkHttp | 型安全 HTTP クライアント |
| **画像** | Coil 2.6 | Kotlin ファースト、コルーチンネイティブ |
| **動画** | AndroidX Media3 | ExoPlayer + OkHttp データソース |
| **ビルド** | Gradle 9.4.1 + AGP 9.2.1 | バージョンカタログで依存管理 |

---

## 主な技術的ハイライト

### 1. okdownload レジュームダウンロードエンジン
[lingochamp/okdownload](https://github.com/lingochamp/okdownload) をダウンロードコアとして統合：
- **レジューム**：ネットワーク切断後にブレークポイントから自動再開
- **コルーチン駆動**：CoroutineScope 内で直接ダウンロードを実行
- **Cloudflare 認証情報注入**：`sharedOkHttpClient` を再利用し、cf_clearance / User-Agent を自動付与

### 2. Cloudflare チャレンジ自動回避
対象サイトは Cloudflare 保護下にあり、通常の OkHttp リクエストは 403 で遮断されます。`CloudflareManager` が非表示 WebView で JS チャレンジを実行し、`cf_clearance` Cookie を抽出して User-Agent を紐付け、以降の全 OkHttp リクエストに注入します。

### 3. スマートインターセプターチェーン
- **ドメイン限定注入**：`pawchive.pw` メインドメインにのみ認証情報を注入、画像 CDN サブドメインには注入しない
- **ノンブロッキング 403 フォールバック**：403 時に強制リフレッシュで 1 回自動再試行
- **デュアル OkHttpClient**：CF インターセプター付きクライアントと軽量クライアントを分離

### 4. シングル Activity + モジュラーナビゲーション
- メイン Tab の Fragment はキャッシュされ再利用、切り替え時に再構築されない
- **AppNavigator インターフェース**：各 feature モジュールはインターフェース経由で遷移、モジュール間の直接依存はゼロ

### 5. スケルトンローディング
カスタム `SkeletonHelper` で shimmer パルスアニメーションを実装、コンテンツ読み込み完了後に 200ms のフェード遷移。

---

## プロジェクト構成

```
Pawchive/
├── app/                          # アセンブリ層：Application / MainActivity
├── feature-common/               # 共通 UI：SkeletonHelper / アダプター
├── feature-home/                 # ホーム
├── feature-search/               # 検索
├── feature-post/                 # 投稿詳細 / 画像ビューア / フルスクリーン動画
├── feature-downloads/            # ダウンロードセンター
├── feature-settings/             # 設定
├── feature-account/              # アカウント / ログイン / ブックマーク
├── data/                         # ビジネス層：Repository / Manager
├── core/                         # インフラ：ネットワーク / モデル / Room
└── gradle/libs.versions.toml     # バージョンカタログ
```

**依存方向**：`:app` → `:feature-*` → `:data` → `:core`

---

## クイックスタート

### 要件
- **Android Studio** Meerkat (2024.3+) 以上
- **JDK** 17+
- **Gradle** 9.2+（ラッパー同梱）

### クローン & ビルド

```bash
git clone https://github.com/FengByX/Pawchive.git
cd Pawchive
./gradlew assembleRelease
```

> APK 出力先：`app/build/outputs/apk/release/Pawchive-v1.6.6.apk`

### インストール

[Releases](https://github.com/FengByX/Pawchive/releases) ページから最新 APK をダウンロードし、Android 11+ 端末にインストールしてください。

---

## 権限

| 権限 | 用途 |
|------|------|
| `INTERNET` | ネットワークリクエスト |
| `ACCESS_NETWORK_STATE` | ネットワーク状態検出 |
| `POST_NOTIFICATIONS` | ダウンロード進捗通知（Android 13+） |
| `FOREGROUND_SERVICE` | ダウンロードフォアグラウンドサービス |

---

## コントリビューション

Issue と Pull Request を歓迎します。提出前に以下を確認してください：
1. コードスタイルを既存コードに合わせる
2. 新機能には 3 言語の文字列リソースを追加する
3. Material Design 3 ガイドラインに従う

---

## ライセンス

本プロジェクトは **MIT License** の下でオープンソース化されています。

---

<p align="center">
  <sub>アイコンは <a href="https://lucide.dev">Lucide</a> より · Material Design 3 に触発されています</sub>
</p>
