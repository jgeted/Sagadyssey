param(
    [string]$OutputDirectory = "$PSScriptRoot\..\src\main\resources\assets\sagadyssey\textures\entity\npc\identity",
    [string]$ComparisonPreviewPath = "$PSScriptRoot\..\docs\npc-concept-gallery\11-male-female-identity-style-match.png"
)

Add-Type -AssemblyName System.Drawing

function C([int]$r, [int]$g, [int]$b, [int]$a = 255) {
    [System.Drawing.Color]::FromArgb($a, $r, $g, $b)
}

function Shade([System.Drawing.Color]$color, [double]$factor) {
    C ([Math]::Min(255, [int]($color.R * $factor))) `
      ([Math]::Min(255, [int]($color.G * $factor))) `
      ([Math]::Min(255, [int]($color.B * $factor))) $color.A
}

function Fill-Woven($bitmap, [System.Drawing.Color]$base, [System.Drawing.Color]$secondary) {
    for ($y = 0; $y -lt 64; $y++) {
        for ($x = 0; $x -lt 64; $x++) {
            $color = if (($x + $y) % 11 -eq 0) { $secondary }
                     elseif (($x + $y) % 2 -eq 0) { Shade $base 0.88 }
                     else { $base }
            $bitmap.SetPixel($x, $y, $color)
        }
    }
}

function Line($bitmap, [int]$x1, [int]$y1, [int]$x2, [int]$y2,
              [System.Drawing.Color]$color) {
    for ($y = $y1; $y -le $y2; $y++) {
        for ($x = $x1; $x -le $x2; $x++) { $bitmap.SetPixel($x, $y, $color) }
    }
}

function Fill($bitmap, [int]$left, [int]$top, [int]$right, [int]$bottom,
              [System.Drawing.Color]$color) {
    Line $bitmap $left $top $right $bottom $color
}

function Pixel($bitmap, [int]$x, [int]$y, [System.Drawing.Color]$color) {
    $bitmap.SetPixel($x, $y, $color)
}

function Add-StatusDetails($bitmap, [string]$status, [System.Drawing.Color]$trim,
                           [string]$gender) {
    $tier = @('commoner','freeman','burgher','knight','lord','duke','king').IndexOf($status)
    if ($tier -ge 1) {
        Line $bitmap 20 20 27 20 $trim
        Line $bitmap 4 20 7 20 $trim
        Line $bitmap 44 20 47 20 $trim
    }
    if ($tier -ge 2) {
        Line $bitmap 23 20 24 31 $trim
        Line $bitmap 19 52 20 63 $trim
    }
    if ($tier -ge 3) {
        Line $bitmap 20 30 27 31 $trim
        Line $bitmap 0 46 23 47 $trim
    }
    if ($tier -ge 4) {
        Line $bitmap 0 44 23 45 $trim
        Line $bitmap 24 44 55 45 $trim
    }
    if ($tier -ge 5) {
        Line $bitmap 24 44 25 63 $trim
        Line $bitmap 54 44 55 63 $trim
    }
    if ($tier -ge 6) {
        # Limited pale fur dots; crowns and sceptres remain separate equipment.
        for ($x = 0; $x -le 22; $x += 3) {
            $bitmap.SetPixel($x, 46, (C 222 214 194))
        }
    }
    if ($gender -eq 'female') {
        # Long-dress center seams and hem use the model skirt UV regions.
        Line $bitmap 15 32 16 63 $trim
        Line $bitmap 47 32 48 63 $trim
        Line $bitmap 0 62 63 63 (Shade $trim 0.82)

        # Commoner and freeman use separate linen apron geometry in the upper-right UV region.
        if ($tier -le 1) {
            $linen = if ($status -eq 'commoner') { C 205 196 174 } else { C 218 210 190 }
            $linenShade = Shade $linen 0.88
            Line $bitmap 48 0 63 31 $linen
            Line $bitmap 48 0 48 31 $linenShade
            Line $bitmap 63 0 63 31 $linenShade
            Line $bitmap 55 0 56 31 (Shade $linen 0.95)
            Line $bitmap 48 29 63 31 $linenShade
        }
    }
}

function Paint-Female-HighResolution($bitmap, [string]$status, $palette) {
    $tier = @('commoner','freeman','burgher','knight','lord','duke','king').IndexOf($status)
    $base = Shade $palette.Base 1.04
    $deep = Shade $base 0.72
    $shadow = Shade $base 0.84
    $light = Shade $base 1.13
    $trim = $palette.Trim
    $linen = if ($tier -le 1) { C 224 216 196 } else { C 234 228 211 }
    $leather = C 75 51 34

    # Standard high-resolution torso front occupies 40..55 / 40..63.
    Fill $bitmap 40 40 55 63 $base
    Fill $bitmap 40 40 41 63 $shadow
    Fill $bitmap 54 40 55 63 $deep
    Pixel $bitmap 42 42 $light; Pixel $bitmap 53 43 $light

    # Linen underdress forms a modest square or shallow-V neckline.
    Fill $bitmap 45 40 50 40 $linen
    Fill $bitmap 44 41 51 42 $linen
    Fill $bitmap 45 43 50 43 $linen
    Pixel $bitmap 46 44 $linen; Pixel $bitmap 49 44 $linen

    # Belt and vertical opening prevent a flat single-color torso.
    $belt = if ($tier -ge 3) { Shade $trim 0.82 } else { $leather }
    Fill $bitmap 40 57 55 59 $belt
    Fill $bitmap 47 57 48 63 (Shade $belt 0.82)
    Fill $bitmap 47 58 48 58 (Shade $trim 1.08)

    if ($tier -ge 2) {
        Pixel $bitmap 43 45 $light; Pixel $bitmap 52 45 $light
        Fill $bitmap 42 61 53 62 $trim
    }
    if ($tier -ge 4) {
        Fill $bitmap 42 44 43 56 $trim
        Fill $bitmap 52 44 53 56 $trim
        Pixel $bitmap 44 45 $trim; Pixel $bitmap 51 45 $trim
    }
    if ($tier -ge 6) {
        Pixel $bitmap 46 48 $trim; Pixel $bitmap 49 48 $trim
        Pixel $bitmap 45 51 $trim; Pixel $bitmap 50 51 $trim
        Pixel $bitmap 46 54 $trim; Pixel $bitmap 49 54 $trim
    }

    # Both slim-arm fronts receive matching linen inner cuffs.
    Fill $bitmap 88 56 95 62 $linen
    Fill $bitmap 88 56 95 57 $trim
    Fill $bitmap 72 118 79 124 $linen
    Fill $bitmap 72 118 79 119 $trim
}

function Paint-Male-HighResolution($bitmap, [string]$status, $palette) {
    $tier = @('commoner','freeman','burgher','knight','lord','duke','king').IndexOf($status)
    $base = $palette.Base
    $deep = Shade $base 0.72
    $shadow = Shade $base 0.84
    $light = Shade $base 1.13
    $trim = $palette.Trim
    $linen = if ($tier -le 1) { C 214 205 184 } else { C 229 220 201 }
    $leather = C 73 49 32

    # Use the same 16x24 effective torso density and woven-light hierarchy as the female garment.
    Fill $bitmap 40 40 55 63 $base
    Fill $bitmap 40 40 41 63 $shadow
    Fill $bitmap 54 40 55 63 $deep
    Pixel $bitmap 42 42 $light; Pixel $bitmap 53 43 $light

    # A high medieval tunic opening: narrow linen collar, reinforced slit and fastening.
    Fill $bitmap 45 40 50 40 $linen
    Fill $bitmap 44 41 51 41 $linen
    Fill $bitmap 46 42 49 43 (Shade $linen 0.92)
    Fill $bitmap 47 43 48 55 $shadow
    Pixel $bitmap 48 45 $light

    $belt = if ($tier -ge 3) { Shade $trim 0.82 } else { $leather }
    Fill $bitmap 40 57 55 59 $belt
    Fill $bitmap 47 57 48 59 (Shade $belt 0.72)
    Pixel $bitmap 47 58 (Shade $trim 1.08); Pixel $bitmap 48 58 (Shade $trim 1.08)

    if ($tier -ge 2) {
        Pixel $bitmap 43 45 $light; Pixel $bitmap 52 45 $light
        Fill $bitmap 42 61 53 62 $trim
    }
    if ($tier -ge 3) {
        Fill $bitmap 42 44 43 56 $trim
        Fill $bitmap 52 44 53 56 $trim
    }
    if ($tier -ge 5) {
        Fill $bitmap 45 45 45 55 (Shade $trim 0.86)
        Fill $bitmap 50 45 50 55 (Shade $trim 0.86)
    }
    if ($tier -ge 6) {
        Pixel $bitmap 46 47 $trim; Pixel $bitmap 49 47 $trim
        Pixel $bitmap 46 51 $trim; Pixel $bitmap 49 51 $trim
        Pixel $bitmap 46 55 $trim; Pixel $bitmap 49 55 $trim
    }

    # Match the female sleeve material resolution while retaining a straighter male cuff.
    Fill $bitmap 88 56 95 62 $linen
    Fill $bitmap 88 56 95 57 $trim
    Fill $bitmap 72 118 79 124 $linen
    Fill $bitmap 72 118 79 119 $trim
}

$statuses = [ordered]@{
    commoner = @{ Base = C 139 126 96; Secondary = C 105 92 69; Trim = C 91 76 54 }
    freeman = @{ Base = C 106 99 70; Secondary = C 77 72 54; Trim = C 156 136 89 }
    burgher = @{ Base = C 124 54 51; Secondary = C 83 42 40; Trim = C 177 127 70 }
    knight = @{ Base = C 49 70 98; Secondary = C 35 50 72; Trim = C 195 169 103 }
    lord = @{ Base = C 105 40 43; Secondary = C 69 29 31; Trim = C 194 151 76 }
    duke = @{ Base = C 39 59 96; Secondary = C 28 43 71; Trim = C 191 146 63 }
    king = @{ Base = C 82 43 84; Secondary = C 55 30 58; Trim = C 219 207 177 }
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
foreach ($gender in @('male', 'female')) {
    foreach ($entry in $statuses.GetEnumerator()) {
        $logical = [System.Drawing.Bitmap]::new(64, 64,
                [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $base = if ($gender -eq 'female') { Shade $entry.Value.Base 1.04 } else { $entry.Value.Base }
        Fill-Woven $logical $base $entry.Value.Secondary
        Add-StatusDetails $logical $entry.Key $entry.Value.Trim $gender
        $bitmap = [System.Drawing.Bitmap]::new(128, 128,
                [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $graphics.DrawImage($logical, 0, 0, 128, 128)
        $graphics.Dispose()
        if ($gender -eq 'female') {
            Paint-Female-HighResolution $bitmap $entry.Key $entry.Value
        } else {
            Paint-Male-HighResolution $bitmap $entry.Key $entry.Value
        }
        $logical.Dispose()
        $path = Join-Path $OutputDirectory ($entry.Key + '_' + $gender + '.png')
        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        $bitmap.Dispose()
    }
}

# Render the exact torso-front UV of both genders as a two-row style comparison.
$preview = [System.Drawing.Bitmap]::new(1050, 286,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$previewGraphics = [System.Drawing.Graphics]::FromImage($preview)
$previewGraphics.Clear((C 226 216 194))
$previewGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$previewGraphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$previewFont = [System.Drawing.Font]::new('Segoe UI', 11, [System.Drawing.FontStyle]::Bold)
$previewBrush = [System.Drawing.SolidBrush]::new((C 62 49 38))
$previewPen = [System.Drawing.Pen]::new((C 91 72 53), 2)
$row = 0
foreach ($gender in @('male', 'female')) {
    $index = 0
    foreach ($status in $statuses.Keys) {
        $source = [System.Drawing.Bitmap]::FromFile((Join-Path $OutputDirectory ($status + '_' + $gender + '.png')))
        $left = 18 + $index * 148
        $top = 10 + $row * 140
        $destination = [System.Drawing.Rectangle]::new($left, $top, 96, 112)
        $previewGraphics.DrawImage($source, $destination, 40, 40, 16, 24, [System.Drawing.GraphicsUnit]::Pixel)
        $previewGraphics.DrawRectangle($previewPen, $destination)
        $previewGraphics.DrawString(($gender + ' / ' + $status), $previewFont, $previewBrush,
                [single]$left, [single]($top + 115))
        $source.Dispose()
        $index++
    }
    $row++
}
New-Item -ItemType Directory -Path (Split-Path $ComparisonPreviewPath) -Force | Out-Null
$preview.Save($ComparisonPreviewPath, [System.Drawing.Imaging.ImageFormat]::Png)
$previewPen.Dispose(); $previewBrush.Dispose(); $previewFont.Dispose()
$previewGraphics.Dispose(); $preview.Dispose()

Write-Host "Generated 14 NPC identity clothing textures: $OutputDirectory"
Write-Host "Male/female identity comparison: $ComparisonPreviewPath"
