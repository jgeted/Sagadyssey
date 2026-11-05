param(
    [string]$Source = "$PSScriptRoot\..\src\main\resources\assets\sagadyssey\textures\entity\npc_farmer.png",
    [string]$OutputDirectory = "$PSScriptRoot\..\src\main\resources\assets\sagadyssey\textures\entity\npc"
)

Add-Type -AssemblyName System.Drawing

function New-Color([int]$red, [int]$green, [int]$blue, [int]$alpha = 255) {
    return [System.Drawing.Color]::FromArgb($alpha, $red, $green, $blue)
}

function Limit-Byte([double]$value) {
    return [Math]::Max(0, [Math]::Min(255, [int][Math]::Round($value)))
}

function Shade-Color([System.Drawing.Color]$color, [double]$factor, [int]$alpha) {
    return New-Color (Limit-Byte ($color.R * $factor)) `
            (Limit-Byte ($color.G * $factor)) `
            (Limit-Byte ($color.B * $factor)) $alpha
}

function Convert-Pixel([System.Drawing.Color]$pixel, $palette) {
    if ($pixel.A -eq 0) {
        return [System.Drawing.Color]::Transparent
    }

    $brightness = ($pixel.R * 0.30 + $pixel.G * 0.59 + $pixel.B * 0.11) / 255.0
    $isSkin = $pixel.R -gt 205 -and $pixel.G -gt 150 -and $pixel.B -gt 105
    $isHair = $pixel.R -lt 115 -and $pixel.G -lt 85 -and $pixel.B -lt 65

    if ($isSkin) {
        return Shade-Color $palette.Skin (0.72 + $brightness * 0.42) $pixel.A
    }
    if ($isHair) {
        return Shade-Color $palette.Hair (0.66 + $brightness * 0.70) $pixel.A
    }

    $isBrightCloth = $pixel.R -gt 150 -and $pixel.G -gt 75
    $target = if ($isBrightCloth) { $palette.Primary } else { $palette.Secondary }
    return Shade-Color $target (0.55 + $brightness * 0.72) $pixel.A
}

function Set-PixelSafe($bitmap, [int]$x, [int]$y, [System.Drawing.Color]$color) {
    if ($x -ge 0 -and $x -lt $bitmap.Width -and $y -ge 0 -and $y -lt $bitmap.Height) {
        $bitmap.SetPixel($x, $y, $color)
    }
}

function Add-ProfessionDetail($bitmap, [string]$name, $palette) {
    # outer torso front: x=20..27, y=36..47
    switch ($name) {
        "warrior" {
            for ($y = 36; $y -le 47; $y++) {
                Set-PixelSafe $bitmap 23 $y $palette.Accent
                Set-PixelSafe $bitmap 24 $y $palette.Accent
            }
        }
        "archer" {
            for ($y = 36; $y -le 47; $y++) {
                $x = 20 + [Math]::Min(7, [int](($y - 36) / 2))
                Set-PixelSafe $bitmap $x $y $palette.Accent
            }
        }
        "heavy" {
            for ($y = 37; $y -le 46; $y += 2) {
                for ($x = 21; $x -le 26; $x += 2) {
                    Set-PixelSafe $bitmap $x $y $palette.Accent
                }
            }
        }
        "worker" {
            for ($x = 20; $x -le 27; $x++) {
                Set-PixelSafe $bitmap $x 43 $palette.Accent
            }
        }
        "blacksmith" {
            for ($y = 37; $y -le 47; $y++) {
                for ($x = 21; $x -le 26; $x++) {
                    Set-PixelSafe $bitmap $x $y (Shade-Color $palette.Secondary 0.68 255)
                }
            }
        }
        "farmer" {
            for ($x = 20; $x -le 27; $x += 2) {
                Set-PixelSafe $bitmap $x 44 $palette.Accent
            }
        }
        "bard" {
            for ($y = 36; $y -le 47; $y++) {
                Set-PixelSafe $bitmap 20 $y $palette.Accent
                Set-PixelSafe $bitmap 27 $y $palette.Accent
            }
        }
        "medic" {
            for ($x = 21; $x -le 26; $x++) {
                Set-PixelSafe $bitmap $x 38 $palette.Accent
            }
            for ($x = 21; $x -le 26; $x++) {
                Set-PixelSafe $bitmap $x 45 $palette.Accent
            }
        }
        "trader" {
            for ($x = 20; $x -le 27; $x++) {
                Set-PixelSafe $bitmap $x 36 $palette.Accent
                Set-PixelSafe $bitmap $x 47 $palette.Accent
            }
        }
    }

    # x=64..127 是职业模型配件的独立 UV 区域。
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 64; $x -lt 128; $x++) {
            $weave = ($x + $y) % 7
            $color = if ($weave -eq 0) {
                Shade-Color $palette.Accent 0.82 255
            } elseif (($x + $y) % 2 -eq 0) {
                Shade-Color $palette.Primary 0.88 255
            } else {
                Shade-Color $palette.Primary 1.05 255
            }
            $bitmap.SetPixel($x, $y, $color)
        }
    }
}

function Add-FaceDetails($bitmap, $palette) {
    # 原始参考皮肤的浅色眼白满足肤色阈值，会在调色时被误染成脸色。
    # 明确重画一像素高的 MC 眼睛，并把原来的第二行眼睛还原为下眼睑。
    $sclera = New-Color 226 218 198
    $iris = $palette.Eye
    Set-PixelSafe $bitmap 9 13 $sclera
    Set-PixelSafe $bitmap 10 13 $iris
    Set-PixelSafe $bitmap 13 13 $iris
    Set-PixelSafe $bitmap 14 13 $sclera
    $lowerLid = Shade-Color $palette.Skin 0.93 255
    foreach ($x in 9, 10, 13, 14) {
        Set-PixelSafe $bitmap $x 14 $lowerLid
    }
}

$palettes = [ordered]@{
    none = @{ Primary = New-Color 111 103 78; Secondary = New-Color 73 69 55; Accent = New-Color 145 130 91; Skin = New-Color 205 157 113; Hair = New-Color 70 48 31; Eye = New-Color 66 91 87 }
    warrior = @{ Primary = New-Color 132 45 38; Secondary = New-Color 82 68 54; Accent = New-Color 191 151 73; Skin = New-Color 201 151 105; Hair = New-Color 62 43 30; Eye = New-Color 69 84 72 }
    archer = @{ Primary = New-Color 54 76 58; Secondary = New-Color 78 61 43; Accent = New-Color 132 103 58; Skin = New-Color 190 137 94; Hair = New-Color 74 50 30; Eye = New-Color 75 91 70 }
    heavy = @{ Primary = New-Color 59 67 76; Secondary = New-Color 67 57 48; Accent = New-Color 142 112 63; Skin = New-Color 185 132 93; Hair = New-Color 52 42 35; Eye = New-Color 71 80 76 }
    worker = @{ Primary = New-Color 86 78 57; Secondary = New-Color 61 54 43; Accent = New-Color 132 100 57; Skin = New-Color 204 154 109; Hair = New-Color 73 49 31; Eye = New-Color 72 88 74 }
    blacksmith = @{ Primary = New-Color 83 79 72; Secondary = New-Color 55 43 34; Accent = New-Color 131 88 47; Skin = New-Color 184 130 90; Hair = New-Color 48 39 34; Eye = New-Color 70 78 69 }
    farmer = @{ Primary = New-Color 63 92 92; Secondary = New-Color 91 70 43; Accent = New-Color 176 143 77; Skin = New-Color 208 161 116; Hair = New-Color 84 55 30; Eye = New-Color 69 92 91 }
    bard = @{ Primary = New-Color 47 72 103; Secondary = New-Color 125 45 48; Accent = New-Color 189 146 67; Skin = New-Color 207 158 113; Hair = New-Color 75 48 29; Eye = New-Color 68 83 102 }
    medic = @{ Primary = New-Color 45 69 67; Secondary = New-Color 210 199 167; Accent = New-Color 116 137 88; Skin = New-Color 194 143 104; Hair = New-Color 64 47 36; Eye = New-Color 68 88 80 }
    trader = @{ Primary = New-Color 48 69 91; Secondary = New-Color 124 52 42; Accent = New-Color 183 139 67; Skin = New-Color 199 148 103; Hair = New-Color 69 44 29; Eye = New-Color 66 82 99 }
}

$sourcePath = (Resolve-Path -LiteralPath $Source).Path
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

foreach ($entry in $palettes.GetEnumerator()) {
    $sourceBitmap = [System.Drawing.Bitmap]::new($sourcePath)
    $outputBitmap = [System.Drawing.Bitmap]::new(128, 64, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) {
            $outputBitmap.SetPixel($x, $y, (Convert-Pixel $sourceBitmap.GetPixel($x, $y) $entry.Value))
        }
    }

    Add-ProfessionDetail $outputBitmap $entry.Key $entry.Value
    Add-FaceDetails $outputBitmap $entry.Value
    $destination = Join-Path $OutputDirectory ($entry.Key + ".png")
    $outputBitmap.Save($destination, [System.Drawing.Imaging.ImageFormat]::Png)
    $outputBitmap.Dispose()
    $sourceBitmap.Dispose()
}

Write-Host "已生成 $($palettes.Count) 张 NPC 职业皮肤：$OutputDirectory"
