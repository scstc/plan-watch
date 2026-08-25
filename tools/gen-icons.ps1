# 生成 plan-watch 图标：
#   - src-tauri/icons/source.png    1024x1024 应用图标源（配合 `npx tauri icon` 使用）
#   - src-tauri/icons/status/*.png  32x32 托盘状态圆点（include_bytes 进二进制）
# 依赖 .NET System.Drawing（Windows 自带）。重新生成后需跑 `npx tauri icon`。

Add-Type -AssemblyName System.Drawing

$root = Split-Path $PSScriptRoot -Parent
$icons = Join-Path $root 'src-tauri/icons'
$statusDir = Join-Path $icons 'status'
New-Item -ItemType Directory -Force $statusDir | Out-Null

function New-StatusDot {
    param($Path, [string]$HexFill, [string]$HexEdge)
    $bmp = New-Object System.Drawing.Bitmap(32, 32)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $brush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml($HexFill))
    $pen = New-Object System.Drawing.Pen([System.Drawing.ColorTranslator]::FromHtml($HexEdge), 2)
    $g.FillEllipse($brush, 4, 4, 24, 24)
    $g.DrawEllipse($pen, 4, 4, 24, 24)
    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

# 托盘状态：灰=无数据 绿=正常 黄=越阈值 红=≥90%
New-StatusDot (Join-Path $statusDir 'idle.png') '#8b949e' '#6e7681'
New-StatusDot (Join-Path $statusDir 'ok.png')   '#3fb950' '#2ea043'
New-StatusDot (Join-Path $statusDir 'warn.png') '#d29922' '#bb8009'
New-StatusDot (Join-Path $statusDir 'crit.png') '#f85149' '#da3633'

# 应用图标：深色圆角方块 + 270° 绿色仪表环 + 中心亮点
$size = 1024
$bmp = New-Object System.Drawing.Bitmap($size, $size)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

$radius = 230
$d = $radius * 2
$round = New-Object System.Drawing.Drawing2D.GraphicsPath
$round.AddArc(0, 0, $d, $d, 180, 90)
$round.AddArc($size - $d, 0, $d, $d, 270, 90)
$round.AddArc($size - $d, $size - $d, $d, $d, 0, 90)
$round.AddArc(0, $size - $d, $d, $d, 90, 90)
$round.CloseFigure()
$bg = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#101820'))
$g.FillPath($bg, $round)

$ring = New-Object System.Drawing.Pen([System.Drawing.ColorTranslator]::FromHtml('#3fb950'), 96)
$ring.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
$ring.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
$g.DrawArc($ring, 192, 192, 640, 640, 135, 270)

$center = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#e6edf3'))
$g.FillEllipse($center, 462, 462, 100, 100)

$bmp.Save((Join-Path $icons 'source.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

Write-Host "icons generated under $icons"
