# WXR Radar — A320 NDスタイル気象レーダー

## 概要
- 気象庁ナウキャスト (降水ナウキャスト) をリアルタイム取得
- A320 Navigation Display スタイルの描画
- **スマホ縦/横画面** と **Android Auto** に対応

---

## セットアップ

### 必要環境
- Android Studio Hedgehog 以降
- JDK 17
- Android SDK 34

### ビルド手順
```bash
# 1. プロジェクトを Android Studio で開く
#    File → Open → この wxr-radar フォルダを選択

# 2. Gradle Sync が自動実行される

# 3. Run (▶) でスマホ/エミュレーターに転送
```

### Google Play Services (FusedLocationProvider) が必要
実機 or Google Play 入りエミュレーターを使用してください。

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

---

## ファイル構成

```
app/src/main/java/com/wxr/radar/
├── WxrApp.kt                      Application クラス
├── data/
│   ├── RadarData.kt               データモデル (RadarData, OwnshipState, NdSettings)
│   └── JmaRepository.kt           気象庁ナウキャスト API クライアント
├── ui/
│   ├── RadarView.kt               A320 ND 描画 View (スマホ用)
│   ├── MainViewModel.kt           GPS・センサー・データ管理
│   └── MainActivity.kt            スマホ画面 Activity
└── auto/
    ├── WxrCarAppService.kt        Android Auto エントリポイント
    ├── WxrSession.kt              Auto セッション
    └── WxrScreen.kt               Auto 描画画面 (SurfaceCallback)
```

---

## ヘディングのソース選択ロジック

```
GPS速度 > 3 km/h  →  GPS Bearing を使用 (最精度)
GPS速度 ≤ 3 km/h  →  磁気センサー (加速度計 + 地磁気) を使用
```

スマホの**物理的な向き**は一切使用しないため、
車のホルダー角度に関わらず正確な進行方向を表示します。

---

## 気象庁ナウキャスト API

| エンドポイント | 内容 |
|---|---|
| `/bosai/nowc/data/nowcast_basetime.json` | 最新観測時刻 |
| `/bosai/nowc/data/radar/{basetime}/{z}/{x}/{y}.png` | レーダータイル (PNG) |

- 5分間隔で自動更新
- タイル取得失敗時はデモデータにフォールバック

---

## TODO / 拡張案
- [ ] ナウキャスト予測 (+10/+20/+30/+40/+50/+60分) の表示
- [ ] TERR レイヤー (国土地理院 標高タイル)
- [ ] 空港アイコン (ARPT レイヤー)
- [ ] Android Auto: SurfaceCallback 上での完全 A320 ND 描画
- [ ] 通知チャンネルによる強雨アラート
