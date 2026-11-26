# 使っているライブラリとライセンス

BoxLedger そのものは MIT(`LICENSE`)です。Android 版(`android/`)は、ビルドのときに次のライブラリを取り込みます。版は `android/gradle/libs.versions.toml` にあります。

## アプリに入る物

| 名前 | 版 | ライセンス | 使う所 |
|---|---|---|---|
| Kotlin 標準ライブラリ(org.jetbrains.kotlin) | 2.0.21 | Apache-2.0 | 全体 |
| kotlinx.coroutines | 1.9.0 | Apache-2.0 | 非同期の処理 |
| AndroidX Core KTX | 1.13.1 | Apache-2.0 | 通知、FileProvider |
| AndroidX Lifecycle | 2.8.6 | Apache-2.0 | ViewModel |
| AndroidX Activity Compose | 1.9.2 | Apache-2.0 | 画面、ファイルや写真の選択 |
| Jetpack Compose(BOM 2024.10.00: ui / material3 / material-icons-extended) | 2024.10.00 | Apache-2.0 | 画面 |
| AndroidX Navigation Compose | 2.8.2 | Apache-2.0 | 画面の行き来 |
| AndroidX Room | 2.6.1 | Apache-2.0 | データの保存 |
| AndroidX Glance(appwidget / material3) | 1.1.0 | Apache-2.0 | ホーム画面のウィジェット |
| AndroidX CameraX(camera-core / camera-camera2 / camera-lifecycle / camera-view) | 1.5.3 | Apache-2.0 | バーコードの読み取りのカメラ |
| ZXing core(com.google.zxing:core) | 3.5.4 | Apache-2.0 | バーコードの読み取り |
| Zip4j(net.lingala.zip4j:zip4j) | 2.11.5 | Apache-2.0 | バックアップの zip と暗号化 |

これらが依存する AndroidX のライブラリ(WorkManager など)も一緒に入ります。どれも Apache-2.0 です。

## テストのときだけ使う物

| 名前 | 版 | ライセンス |
|---|---|---|
| JUnit 4 | 4.13.2 | EPL-1.0 |
| kotlin-test | 2.0.21 | Apache-2.0 |

## 要件定義書から変えた所: ML Kit を使わない

要件定義書は、バーコードの読み取りに Google ML Kit Barcode Scanning を挙げていますが、このアプリは使っていません。ML Kit は Google の独自の規約(ML Kit Terms of Service)の下で配られていて、オープンソースのライセンスではないためです。代わりに、Apache-2.0 の ZXing を使っています。読み取りは端末の中だけで行い、通信はしません。

## 足していない物

JSON のライブラリは足していません。バックアップの `manifest.json` と `data.json` は、`:core` の中の小さな読み書き(`core/backup/Json.kt`)で扱います。

## 写したコード

- `android/core/src/main/kotlin/.../core/barcode/Barcode.kt` と、そのテストは、同じ作者(takosasi-dev)の ShelfTally(MIT)から写しました。元は同じ作者の DeskSlate(MIT)の probe です。
- バーコードの読み取りの画面、写真の取り込み、zip の読み書き、自動バックアップ、アプリロック、ウィジェット、通知、紙吹雪の書き方は、同じ作者の ShelfTally と収支管理アプリ(win-loss-tracker、どちらも MIT)に合わせています。

## 同梱していない物

実在のゲーム・企業・商品の画像、箱絵、アイコン、ロゴ、音、フォントは入れていません。ゲームの名前は利用者が打ち込みます。アプリのアイコンと通知のアイコンは、自分で描いたベクターです。画面のアイコンは Material Icons(Apache-2.0、Compose の material-icons-extended に含まれる)です。
