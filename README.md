# WXR Moto — 航空機ND風 バイク向けライブ気象レーダー

## 概要

**走行中のバイクライダーが、チラ見1秒で「進行方向の先に雨雲があるか」を読み取れる**、航空機ND（Navigation Display）スタイルのライブ気象レーダー。

- 気象庁ナウキャスト（降水）をリアルタイム取得（5分更新）
- 3層構成: 道路基図（オフラインPMTiles）＋ JMA雨雲ラスター ＋ ND計器オーバーレイ
- ヘディングアップ既定 / ARC（前方扇形）・ROSE（全周）切替
- **スマホ縦/横画面** と **Android Auto** に対応
- 地図データ © OpenStreetMap contributors

詳細仕様は `../wxr_moto_spec.md`、実装計画は `../wxr_moto_implementation_plan.md` を参照。

---

## rev3: 実車テスト反映（2026-06）

実車テストで判明した7項目を改良。**実機での検証ポイント**を併記する。

### 1. 方位のふらつき・180°反転対策【最優先】
- 方位センサーを `TYPE_ROTATION_VECTOR`（ジャイロ統合済み）に変更。生の地磁気+加速度は廃止
- **GPS速度 5km/h 未満では方位を更新せず、直前の有効なGPS方位で凍結**（停車中の反転の主因を排除）
- センサー方位は「起動後まだ一度もGPS方位が確定していない間」のみ暫定使用
- 表示平滑化: 最短回転方向ローパス（デッドバンド ~1° / 係数 ~0.08）
- GPS / CMP のソースインジケーターは維持
- 検証: 停車中に車体を揺らしても方位が動かない・反転しないこと。走り出すと GPS 表示に変わり追従すること

### 2. UI整理
- 縦画面: 上にレーダー（正方形・主役）、下に情報バー＋**ボタングリッド（56dp）**
- 横画面: 左パネル（MODE/ORIENT/HDG/GS）＋中央レーダー＋右パネル（RANGE/単位/WXR/位置）
- MODE・ORIENT は1ボタントグル化、TERR/ARPT（未実装レイヤー）のボタンは非表示化
- 検証: グローブでも押せるか。縦/横の回転でレイアウトが切り替わるか

### 3. 距離リングの視認性
- ラベルを大きく・太字・黒縁取り（シャドウ）に。最外周は単位付きでさらに強調
- 検証: 走行中のチラ見でレンジが読めるか

### 4. レンジ8段化（km基準）
- `1, 3, 5, 10, 30, 50, 100, 300 km`、初期値 10km
- 単位 NM ⇄ km はボタン切替（**表示専用**。データ取得・カメラzoomは常にkm基準）
- `NdSettings.rangeKm`(Int, km) が唯一の内部表現。`MapController` の zoom 算出もこれを参照

### 5. オートレンジ（車速連動・ON/OFF）
- AUTO ボタンで ON/OFF。マッピング: <10km/h→3km, <30→5km, <60→10km, <90→30km, それ以上→50km
- 境界チャタリング防止: 推奨レンジが3秒安定してから切替
- **手動でレンジ操作すると AUTO は自動OFF**
- 検証: 加減速でレンジが追従すること、RNG± を押すと AUTO が消灯すること

### 6. 警報の消音
- アラートバナーに「✕ 消音」ボタン追加。**バナー自体のタップでも消音**
- 一度消音したら、前方の強雨（>80mm/h）が一旦解消するまで再表示しない。解消後の次の強雨では再び表示
- 検証: 消音→バナーが消え、雨域通過後に新たな強雨で再表示されること

### 7. 画面スリープ無効化【最重要】
- `FLAG_KEEP_SCREEN_ON` をアプリ稼働中は常時設定。走行中に画面が消えない
- 検証: 無操作で放置しても画面が消灯しないこと

---

## アーキテクチャ（3層）

```
[最前面] NdOverlayView      … 透過の計器オーバーレイ (コンパス/リング/自機/マスク)
[中間]   JMA雨雲            … MapLibre RasterSource (ナウキャストXYZタイル, 5分毎に差し替え)
[最背面] 道路基図           … MapLibre VectorSource (pmtiles://asset://japan.pmtiles)
```

- ヘディングアップ = MapLibre カメラ `bearing`（計器の平滑化ヘディングと同期）
- ARCモード = カメラ `padding` で自機を画面下78%へオフセット
- レンジ切替 = レンジ[km]↔リング半径[px] が一致する `zoom` を算出

---

## セットアップ

### 必要環境
- Android Studio Hedgehog 以降
- JDK 17
- Android SDK 34

### ビルド手順
```bash
# 1. （任意）道路基図を生成して同梱する
#    tools/generate_pmtiles.ps1 参照 → app/src/main/assets/japan.pmtiles に配置
#    ※未配置でもビルド・起動可能（基図なし、"NO BASEMAP" 表示）

# 2. プロジェクトを Android Studio で開く
#    File → Open → この wxr-radar フォルダを選択

# 3. Gradle Sync 後、Run (▶) でスマホ/エミュレーターに転送
```

### Google Play Services (FusedLocationProvider) が必要
実機 or Google Play 入りエミュレーターを使用してください。

---

## 道路基図（PMTiles）の生成

`tools/generate_pmtiles.ps1`（Windows PowerShell）を実行すると、Planetiler で
OSM 日本データから `japan.pmtiles` を生成できます。要 Java 21+・メモリ4GB+。

```powershell
cd tools
./generate_pmtiles.ps1            # 日本全体 (初回DL ~2GB, 生成数十分)
./generate_pmtiles.ps1 -Area kanto  # 開発用に関東のみ (高速)
```

生成後 `app/src/main/assets/japan.pmtiles` にコピーしてビルドすると、
機内モードでも道路基図が表示されます（受け入れ基準 #1）。

> ファイルが100MBを超える場合は assets 同梱をやめ、初回ダウンロード方式
> （`file://` 参照）か Play Asset Delivery を検討（仕様書 §6.2）。

---

## Android Auto テスト手順

### Desktop Head Unit (DHU) でテスト
```bash
# 1. Android Auto アプリをスマホにインストール
# 2. スマホの開発者オプション → Unknown sources を許可
# 3. Android Studio の Tools → Android Auto → Desktop Head Unit を起動
# 4. スマホをPCにUSB接続
# 5. DHU が起動したらアプリが自動表示される
```

### DHU インストール
Android Studio の SDK Manager → SDK Tools → Android Auto Desktop Head Unit Emulator

> 注: v1 の Android Auto は従来どおり Canvas 直描き（雨雲＋計器、道路基図なし）。
> Auto への基図表示は実装計画 §7-1 の方式判断後に対応。

---

## ファイル構成

```
app/src/main/java/com/wxr/radar/
├── WxrApp.kt                      Application クラス (MapLibre 初期化)
├── data/
│   ├── RadarData.kt               データモデル (RadarData, OwnshipState, NdSettings)
│   └── JmaRepository.kt           気象庁ナウキャスト API クライアント (ステータス/アラート判定用)
├── map/
│   └── MapController.kt           MapLibre 制御 (基図PMTiles・雨雲ラスター・カメラ同期)
├── ui/
│   ├── NdOverlayView.kt           ND計器オーバーレイ (透過View)
│   ├── RadarView.kt               (旧実装の残置ファイル: NdOverlayView へ移行済み)
│   ├── MainViewModel.kt           GPS・センサー・データ管理
│   └── MainActivity.kt            スマホ画面 Activity (MapView ライフサイクル管理)
└── auto/
    ├── WxrCarAppService.kt        Android Auto エントリポイント
    ├── WxrSession.kt              Auto セッション
    └── WxrScreen.kt               Auto 描画画面 (SurfaceCallback)
app/src/main/assets/
├── wxr_basemap_dark_style.json    道路+水域のみのダークスタイル
└── japan.pmtiles                  道路基図 (tools で生成・同梱。リポジトリには含めない)
tools/
└── generate_pmtiles.ps1           Planetiler による PMTiles 生成スクリプト
```

---

## ヘディングのソース選択ロジック

```
GPS速度 > 3 km/h  →  GPS Bearing を使用 (最精度)
GPS速度 ≤ 3 km/h  →  磁気センサー (加速度計 + 地磁気) を使用
```

スマホの**物理的な向き**は一切使用しないため、
ホルダーの取付角度に関わらず正確な進行方向を表示します。

---

## 気象庁ナウキャスト API

| エンドポイント | 内容 |
|---|---|
| `/bosai/jmatile/data/nowc/targetTimes_N1.json` | 最新観測時刻一覧 (先頭が最新) |
| `/bosai/jmatile/data/nowc/{basetime}/none/{validtime}/surf/hrpns/{z}/{x}/{y}.png` | 降水タイル (PNG) |

- 5分間隔で自動更新（URL に時刻を含むため古いタイルは残らない）
- 受信状態は「JMA LIVE / 欠測 / 通信エラー」を区別して表示

---

## GitHub Actions で自動ビルド（PC不要）

このリポジトリは push するたびに GitHub Actions が自動で APK をビルドします。

### APK の入手手順
1. GitHub の **Actions** タブを開く
2. 最新の成功した実行（緑チェック）をタップ
3. ページ下部の **Artifacts** → `wxr-radar-debug` をダウンロード
4. ZIP を展開して `app-debug.apk` を端末にインストール

> 初回インストール時はスマホ設定で「提供元不明のアプリのインストール」を許可してください。

### ビルド失敗時
エラー内容が自動で **Issue** に投稿されるので、Actions のログを開かなくても原因を確認できます。

---

## TODO / 拡張案（v1スコープは仕様書 §5.1 参照）
- [x] 基本ビルドを通す（CI 成功）
- [x] Android Auto（car-app）機能の有効化
- [x] Android Auto 側の GPS 位置取得連携
- [x] アダプティブ・ランチャーアイコン
- [x] MapLibre + PMTiles 道路基図（3層構成への移行）
- [x] JMA雨雲の MapLibre ラスター化（手動タイル合成の廃止）
- [ ] japan.pmtiles の生成・同梱（tools/generate_pmtiles.ps1）
- [ ] 実機での描画・GPS・気象庁データ取得の動作確認（Phase 0 自己検証）
- [ ] Android Auto への道路基図表示（実装計画 §7-1）
- [ ] ナウキャスト予測 (+10〜+60分) の表示（v1.1 / Pro候補）
- [ ] 通知チャンネルによる強雨アラート（v1.1 / Pro候補）
- [ ] リリースビルドの署名設定
