# Descarga VLC portable (64 bits) y copia LibVLC + plugins a desktop/resources/windows/vlc
# para que el instalador de VIVI Music lo incluya y funcione sin instalar VLC aparte.
$ErrorActionPreference = "Stop"
$version = "3.0.21"
$root = Split-Path -Parent $PSScriptRoot
$target = Join-Path $root "resources\windows\vlc"
if (Test-Path (Join-Path $target "libvlc.dll")) { Write-Host "VLC ya presente en $target"; exit 0 }

$zip = Join-Path $env:TEMP "vlc-$version-win64.zip"
$url = "https://download.videolan.org/pub/videolan/vlc/$version/win64/vlc-$version-win64.zip"
Write-Host "Descargando $url"
Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing

$tmp = Join-Path $env:TEMP "vlc-extract"
if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
Expand-Archive -Path $zip -DestinationPath $tmp
$src = Join-Path $tmp "vlc-$version"

New-Item -ItemType Directory -Force -Path $target | Out-Null
Copy-Item (Join-Path $src "libvlc.dll") $target
Copy-Item (Join-Path $src "libvlccore.dll") $target
Copy-Item (Join-Path $src "plugins") $target -Recurse

# Quita plugins que una app de solo audio no necesita (reduce ~40 MB)
foreach ($d in @("gui", "video_output", "video_filter", "video_splitter", "visualization")) {
    $p = Join-Path $target "plugins\$d"
    if (Test-Path $p) { Remove-Item -Recurse -Force $p }
}
Write-Host "VLC $version listo en $target"
