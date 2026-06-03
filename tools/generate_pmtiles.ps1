# ============================================================
#  WXR Moto 道路基図 (japan.pmtiles) 生成スクリプト
#
#  使い方 (PowerShell):
#    cd tools
#    ./generate_pmtiles.ps1                # 日本全体 (初回DL ~2GB / 生成数十分)
#    ./generate_pmtiles.ps1 -Area kanto    # 開発用に関東のみ (高速)
#    ./generate_pmtiles.ps1 -MaxZoom 11    # さらに小型化したい場合
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

$ErrorActionPreference = "Stop"

# ---- Planetiler の取得 (単一 jar) ----
$PlanetilerVersion = "0.8.4"   # 実行時に最新を確認: github.com/onthegomap/planetiler/releases
$Jar = "planetiler.jar"
if (-not (Test-Path $Jar)) {
    $url = "https://github.com/onthegomap/planetiler/releases/download/v$PlanetilerVersion/planetiler.jar"
    Write-Host "Planetiler をダウンロード中: $url"
    Invoke-WebRequest -Uri $url -OutFile $Jar
}

# ---- Java バージョン確認 ----
$javaVer = (& java -version 2>&1 | Select-Object -First 1)
Write-Host "Java: $javaVer"
Write-Host "Area=$Area MaxZoom=$MaxZoom Output=$Output"
Write-Host ""

# ---- 生成 ----
# --download : Geofabrik から OSM 抽出 (.osm.pbf) を自動取得
# メモリは必要に応じて -Xmx を増やす (日本全体なら 4g 推奨)
java -Xmx4g -jar $Jar `
    --download `
    --area=$Area `
    --maxzoom=$MaxZoom `
    --output=$Output

if (Test-Path $Output) {
    $sizeMB = [math]::Round((Get-Item $Output).Length / 1MB, 1)
    Write-Host ""
    Write-Host "生成完了: $Output ($sizeMB MB)"
    if ($sizeMB -gt 100) {
        Write-Host "⚠ 100MB 超: assets 同梱は非推奨。道路・水域のみに絞る構成か初回DL方式を検討 (仕様書 §6.2)"
    } else {
        Write-Host "次の手順: Copy-Item $Output ../app/src/main/assets/japan.pmtiles"
    }
}
