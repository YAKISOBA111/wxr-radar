# ============================================================
#  WXR Moto 道路基図 (japan.pmtiles) 生成スクリプト
#
#  使い方 (PowerShell):
#    powershell -ExecutionPolicy Bypass -File generate_pmtiles.ps1 -Area kanto
#    -Area japan   : 日本全体 (初回DL ~2GB / 生成数十分)
#    -Area kanto   : 開発用に関東のみ (高速)
#    -MaxZoom 11   : さらに小型化したい場合
#
#  必要環境:
#    - Java 21 以降 (java -version で確認)
#    - メモリ 4GB 以上 / ディスク空き ~10GB (日本全体の場合)
#
#  出力:
#    ./japan.pmtiles
#    → app/src/main/assets/japan.pmtiles にコピーして同梱する
#    → 100MB を超える場合は assets 同梱をやめ、初回DL方式を検討 (仕様書 §6.2)
# ============================================================
param(
    [string]$Area    = "japan",   # Geofabrik の地域名 (japan / kanto / kansai など)
    [int]   $MaxZoom = 12,        # 背景用途のため 12 で十分 (z14 はファイル肥大)
    [string]$Output  = "japan.pmtiles"
)

# 注意: java など stderr に出力するネイティブコマンドを止めないよう "Continue" にする
#       (PS5.1 では Stop + 2>&1 で正常な stderr 出力も NativeCommandError になる)
$ErrorActionPreference = "Continue"

# ---- Planetiler の取得 (単一 jar) ----
$PlanetilerVersion = "0.8.4"   # 最新は github.com/onthegomap/planetiler/releases
$Jar = Join-Path $PSScriptRoot "planetiler.jar"
if (-not (Test-Path $Jar)) {
    $url = "https://github.com/onthegomap/planetiler/releases/download/v$PlanetilerVersion/planetiler.jar"
    Write-Host "Planetiler をダウンロード中: $url"
    Invoke-WebRequest -Uri $url -OutFile $Jar
    if (-not (Test-Path $Jar)) { Write-Host "ダウンロード失敗"; exit 1 }
}

# ---- Java 確認 (stderr 経由でも止まらないよう cmd /c でラップ) ----
$javaVer = cmd /c "java -version 2>&1" | Select-Object -First 1
if (-not $javaVer) { Write-Host "java が見つかりません。JDK 21 をインストールしてください"; exit 1 }
Write-Host "Java: $javaVer"
Write-Host "Area=$Area MaxZoom=$MaxZoom Output=$Output"
Write-Host ""

# ---- 生成 ----
# --download : Geofabrik から OSM 抽出 (.osm.pbf) を自動取得
# Planetiler のログは stderr に出るため赤く表示されるが正常動作
$OutPath = Join-Path $PSScriptRoot $Output
java -Xmx4g -jar $Jar --download "--area=$Area" "--maxzoom=$MaxZoom" "--output=$OutPath"
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "✕ Planetiler がエラー終了しました (exit=$LASTEXITCODE)。上のログを確認してください"
    exit $LASTEXITCODE
}

if (Test-Path $OutPath) {
    $sizeMB = [math]::Round((Get-Item $OutPath).Length / 1MB, 1)
    Write-Host ""
    Write-Host "生成完了: $OutPath ($sizeMB MB)"
    if ($sizeMB -gt 100) {
        Write-Host "⚠ 100MB 超: assets 同梱は非推奨。道路・水域のみに絞る構成か初回DL方式を検討 (仕様書 §6.2)"
    } else {
        Write-Host "次の手順: Copy-Item `"$OutPath`" `"$PSScriptRoot\..\app\src\main\assets\japan.pmtiles`""
    }
}
