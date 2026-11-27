# Changelog

書き方: [Keep a Changelog](https://keepachangelog.com/ja/1.1.0/)。

## [Unreleased]

### Changed
- Android 版の画面を Material 3 Expressive で作り直した(大きな見出し、まとまったリスト、淡い地のアイコン)。material3 は 1.5.0-alpha12

### Fixed
- Android 版の下のナビの「コレクション」が、幅 411dp で切れていた

### Added
- Android 版の画面を PC の上で画像にするテスト(`gradlew testDebugUnitTest -Pshots`。Roborazzi と Robolectric)
- Android 版(`android/`)
  - ゲームの登録・編集・削除・アーカイブ、箱の写真、ジャンルタグ、5 段階の評価、ウィッシュリスト
  - 拡張の管理(本体にぶら下げる。プレイ記録で使った拡張を選ぶ)、外貨での購入の記録
  - バーコードの読み取り(CameraX + ZXing。ML Kit は使わない)
  - プレイヤーの管理、プレイ記録(スコアから順位と勝者を自動で入れる)、プレイヤー別の成績、ゲーム別の統計
  - 貸し出しの記録と、返却予定日の前日・当日の通知
  - 統計のダッシュボード、年間の振り返り、未プレイの提案
  - 検索・絞り込み・並び替え、グリッドとリストの切り替え
  - zip のバックアップ(パスワード、自動バックアップ)。ほかの端末の zip の追加取り込み(差分マージ)と全置換
  - ホーム画面のウィジェットとショートカット、配色の切り替え、ダークモード、アプリロック

実機では未確認。
