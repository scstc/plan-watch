# 生成 plan-watch 胶囊风格图标：
#   - src-tauri/icons/source.png    1024x1024 应用图标源（配合 `npx tauri icon` 使用）
#   - src-tauri/icons/status/*.png  32x32 托盘状态胶囊（include_bytes 进二进制）
# 依赖 .NET System.Drawing（Windows 自带）。重新生成后需跑 `npx tauri icon`。

Add-Type -AssemblyName System.Drawing

$root = Split-Path $PSScriptRoot -Parent
$icons = Join-Path $root 'src-tauri/icons'
$statusDir = Join-Path $icons 'status'
New-Item -ItemType Directory -Force $statusDir | Out-Null

function New-CapsulePath {
    # 水平胶囊：两个半圆端 + 上下直线
    param([float]$X, [float]$Y, [float]$W, [float]$H)
    $r = $H / 2
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc($X, $Y, $H, $H, 90, 180)          # 左端半圆
    $p.AddArc($X + $W - $H, $Y, $H, $H, 270, 180) # 右端半圆
    $p.CloseFigure()
    return $p
}

function New-StatusCapsule {
    param($Path, [string]$HexFill, [string]$HexEdge)
    $bmp = New-Object System.Drawing.Bitmap(32, 32)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $cap = New-CapsulePath 1 7 30 18
    $brush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml($HexFill))
    $pen = New-Object System.Drawing.Pen([System.Drawing.ColorTranslator]::FromHtml($HexEdge), 2)
    $g.FillPath($brush, $cap)
    $g.DrawPath($pen, $cap)
    $bmp.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose(); $bmp.Dispose()
}

# 托盘图标：左绿右红双色胶囊（品牌固定样式，不随状态变色）
$bmp = New-Object System.Drawing.Bitmap(32, 32)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$cap = New-CapsulePath 1 7 30 18
$g.SetClip($cap)
$green = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#3fb950'))
$red = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#f85149'))
$dark = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#101820'))
$g.FillRectangle($green, 1, 7, 14, 18)
$g.FillRectangle($dark, 15, 7, 2, 18)
$g.FillRectangle($red, 17, 7, 14, 18)
$g.ResetClip()
$pen = New-Object System.Drawing.Pen([System.Drawing.ColorTranslator]::FromHtml('#2f3a44'), 1.5)
$g.DrawPath($pen, $cap)
$bmp.Save((Join-Path $statusDir 'dual.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

# 应用图标：透明底上一枚左绿右红的双色胶囊（可用 → 耗尽的语义渐变）
$size = 1024
$bmp = New-Object System.Drawing.Bitmap($size, $size)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

# 外壳（深色胶囊）
$shell = New-CapsulePath 80 320 864 384
$shellBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#101820'))
$shellPen = New-Object System.Drawing.Pen([System.Drawing.ColorTranslator]::FromHtml('#2f3a44'), 10)
$g.FillPath($shellBrush, $shell)
$g.DrawPath($shellPen, $shell)

# 内芯：裁剪成胶囊形后左半填绿、右半填红，中间深色分隔
$inner = New-CapsulePath 104 344 816 336
$g.SetClip($inner)
$greenBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#3fb950'))
$redBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#f85149'))
$gapBrush = New-Object System.Drawing.SolidBrush([System.Drawing.ColorTranslator]::FromHtml('#101820'))
$g.FillRectangle($greenBrush, 104, 344, 396, 336)
$g.FillRectangle($gapBrush, 500, 344, 24, 336)
$g.FillRectangle($redBrush, 524, 344, 396, 336)

# 顶部高光（同样裁剪在胶囊内）
$gloss = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(46, 255, 255, 255))
$glossPath = New-CapsulePath 128 356 768 100
$g.FillPath($gloss, $glossPath)
$g.ResetClip()

$bmp.Save((Join-Path $icons 'source.png'), [System.Drawing.Imaging.ImageFormat]::Png)
$g.Dispose(); $bmp.Dispose()

Write-Host "capsule icons generated under $icons"
