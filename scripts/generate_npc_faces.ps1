param(
    [string]$OutputDirectory = "$PSScriptRoot\..\src\main\resources\assets\sagadyssey\textures\entity\npc\face",
    [string]$PreviewPath = "$PSScriptRoot\..\docs\npc-concept-gallery\07-female-face-pixel-preview.png",
    [string]$ComparisonPreviewPath = "$PSScriptRoot\..\docs\npc-concept-gallery\10-male-female-face-style-match.png"
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

function Fill($bitmap, [int]$left, [int]$top, [int]$right, [int]$bottom,
              [System.Drawing.Color]$color) {
    for ($y = $top; $y -le $bottom; $y++) {
        for ($x = $left; $x -le $right; $x++) { $bitmap.SetPixel($x, $y, $color) }
    }
}

function Pixel($bitmap, [int]$x, [int]$y, [System.Drawing.Color]$color) {
    $bitmap.SetPixel($x, $y, $color)
}

function Paint-Head($bitmap, $palette, [string]$gender, [string]$face) {
    $skin = $palette.Skin
    $skinShade = Shade $skin 0.88
    $hair = $palette.Hair
    $hairShade = Shade $hair 0.72
    $sclera = C 238 232 218

    # The independent face layer covers every head side; subtle shading avoids flat plastic color.
    Fill $bitmap 0 0 31 15 $skin
    for ($x = 0; $x -le 31; $x += 2) { Pixel $bitmap $x 15 $skinShade }
    Fill $bitmap 8 0 15 7 $hair
    Fill $bitmap 24 8 31 15 $hairShade
    Fill $bitmap 0 8 2 15 $hair
    Fill $bitmap 21 8 23 15 $hair

    if ($gender -eq 'female') {
        # Female faces use a player-style layout: open forehead, paired eyes, tiny nose shade and short mouth.
        # Never simulate the villager nose with a wide shaded block.
        Fill $bitmap 8 8 15 9 $hair
        Fill $bitmap 8 10 9 10 $hair
        Fill $bitmap 14 10 15 10 $hair
        if ($face -eq 'long') {
            Fill $bitmap 8 10 10 10 $hair
            Pixel $bitmap 14 9 $skin
        } elseif ($face -eq 'fair') {
            Pixel $bitmap 11 9 $skin
            Pixel $bitmap 12 9 $skin
        } elseif ($face -eq 'elder') {
            Pixel $bitmap 10 9 $skin
            Pixel $bitmap 13 9 $skin
        }

        $lash = Shade $hair 0.56
        Pixel $bitmap 9 11 $lash; Pixel $bitmap 10 11 $lash
        Pixel $bitmap 13 11 $lash; Pixel $bitmap 14 11 $lash
        Pixel $bitmap 9 12 $sclera; Pixel $bitmap 10 12 $palette.Eye
        Pixel $bitmap 13 12 $palette.Eye; Pixel $bitmap 14 12 $sclera
        Pixel $bitmap 12 13 $skinShade
        Pixel $bitmap 11 14 $palette.Lip; Pixel $bitmap 12 14 $palette.Lip

        $blush = C ([Math]::Min(255, $skin.R + 10)) `
                   ([Math]::Max(0, $skin.G - 12)) ([Math]::Max(0, $skin.B - 10))
        if ($face -in @('young', 'fair')) {
            Pixel $bitmap 8 14 $blush; Pixel $bitmap 15 14 $blush
        }
        if ($face -eq 'broad') {
            Pixel $bitmap 8 13 $skinShade; Pixel $bitmap 15 13 $skinShade
        }
        if ($face -eq 'long') {
            Pixel $bitmap 11 15 $skinShade; Pixel $bitmap 12 15 $skinShade
        }
        if ($face -eq 'weathered' -or $face -eq 'elder') {
            Pixel $bitmap 8 13 $skinShade; Pixel $bitmap 15 13 $skinShade
            Pixel $bitmap 9 15 $skinShade; Pixel $bitmap 14 15 $skinShade
        }
    } else {
        $hairline = if ($face -eq 'long') { 9 } elseif ($face -eq 'elder') { 8 } else { 10 }
        Fill $bitmap 8 8 15 $hairline $hair
        if ($face -eq 'elder') {
            Pixel $bitmap 10 9 $skin
            Pixel $bitmap 13 9 $skin
        }

        Pixel $bitmap 9 11 $hairShade; Pixel $bitmap 10 11 $hairShade
        Pixel $bitmap 13 11 $hairShade; Pixel $bitmap 14 11 $hairShade
        Pixel $bitmap 9 12 $sclera; Pixel $bitmap 10 12 $palette.Eye
        Pixel $bitmap 13 12 $palette.Eye; Pixel $bitmap 14 12 $sclera
        Pixel $bitmap 11 13 $skinShade; Pixel $bitmap 12 13 $skinShade
        Pixel $bitmap 10 14 $skinShade; Pixel $bitmap 11 14 $palette.Lip
        Pixel $bitmap 12 14 $palette.Lip; Pixel $bitmap 13 14 $skinShade

        if ($face -eq 'broad') {
            Pixel $bitmap 8 13 $skinShade; Pixel $bitmap 15 13 $skinShade
        }
        if ($face -eq 'long') {
            Pixel $bitmap 11 15 $skinShade; Pixel $bitmap 12 15 $skinShade
        }
        if ($face -eq 'weathered' -or $face -eq 'elder') {
            Pixel $bitmap 8 12 $skinShade; Pixel $bitmap 15 12 $skinShade
            Pixel $bitmap 9 14 $skinShade; Pixel $bitmap 14 14 $skinShade
        }
        if ($face -eq 'elder') {
            Pixel $bitmap 8 11 $skinShade; Pixel $bitmap 15 11 $skinShade
        }
    }

    # Outer head: male hair, or female braids and linen veils selected by face variant.
    if ($gender -eq 'female' -and $face -in @('long', 'weathered', 'elder')) {
        $cover = if ($face -eq 'elder') { C 190 184 166 } else { C 218 208 184 }
        Fill $bitmap 32 0 63 15 ([System.Drawing.Color]::Transparent)
        Fill $bitmap 40 0 47 7 $cover
        Fill $bitmap 32 8 39 15 (Shade $cover 0.92)
        Fill $bitmap 48 8 55 15 (Shade $cover 0.96)
        Fill $bitmap 56 8 63 15 (Shade $cover 0.88)
        Fill $bitmap 40 8 47 9 $cover
        Pixel $bitmap 40 10 $cover; Pixel $bitmap 47 10 $cover
        Pixel $bitmap 40 11 $cover; Pixel $bitmap 47 11 $cover
        Fill $bitmap 36 16 63 31 $cover
    } elseif ($gender -eq 'female') {
        # Keep the outer hair broken into crown and side locks instead of a helmet-like box.
        Fill $bitmap 32 0 63 15 ([System.Drawing.Color]::Transparent)
        Fill $bitmap 40 0 47 7 $hair
        Fill $bitmap 32 8 39 15 $hairShade
        Fill $bitmap 48 8 55 15 $hairShade
        Fill $bitmap 56 8 63 15 $hair
        Fill $bitmap 40 8 47 8 $hair
        Fill $bitmap 40 9 42 9 $hair
        Fill $bitmap 45 9 47 9 $hair
        Fill $bitmap 40 10 40 13 $hairShade
        Fill $bitmap 47 10 47 13 $hairShade
    } else {
        Fill $bitmap 32 0 63 15 ([System.Drawing.Color]::Transparent)
        Fill $bitmap 40 0 47 7 $hair
        Fill $bitmap 32 8 39 15 $hairShade
        Fill $bitmap 48 8 55 15 $hairShade
        Fill $bitmap 56 8 63 15 $hair
    }

    # Extra model UV regions: nose, beard, braids and veil.
    Fill $bitmap 0 16 7 23 $skinShade
    Fill $bitmap 8 16 23 27 $hair
    Fill $bitmap 24 16 47 31 $hair
    if ($gender -eq 'male' -and $face -in @('broad', 'weathered', 'elder')) {
        Fill $bitmap 8 16 23 27 $hairShade
    }
}

function Paint-Female-DisplayStyle($bitmap, $palette, [string]$face) {
    $skin = $palette.Skin
    $hair = $palette.Hair
    $hairShade = Shade $hair 0.70
    $hairDeep = Shade $hair 0.52
    $hairLight = Shade $hair 1.18
    $sclera = C 240 236 224

    # A 256x128 asset on the 64x32 model UV gives the face an effective 32x32 pixel density.
    # Clean skin, complete eyes and a nearly invisible nose are the key display-reference traits.
    Fill $bitmap 32 32 63 63 $skin

    # Inner hairline steps inward, leaving generous forehead and cheek space.
    Fill $bitmap 32 32 63 37 $hair
    Fill $bitmap 32 38 36 42 $hair
    Fill $bitmap 60 38 63 42 $hair
    Fill $bitmap 32 43 34 51 $hairShade
    Fill $bitmap 61 43 63 51 $hairShade
    Fill $bitmap 37 38 40 39 $hair
    Fill $bitmap 56 38 59 39 $hair

    switch ($face) {
        'young' {
            Fill $bitmap 38 38 43 40 $hair
            Fill $bitmap 54 38 59 39 $hair
            Pixel $bitmap 44 38 $hairLight; Pixel $bitmap 53 38 $hairLight
        }
        'broad' {
            Fill $bitmap 36 38 43 41 $hair
            Fill $bitmap 55 38 61 41 $hair
            Fill $bitmap 32 50 35 55 $hairShade
            Fill $bitmap 60 50 63 55 $hairShade
        }
        'long' {
            Fill $bitmap 36 38 45 39 $hair
            Fill $bitmap 36 40 40 41 $hair
            Pixel $bitmap 56 38 $hairLight
            Fill $bitmap 34 52 36 59 $hairShade
            Fill $bitmap 59 52 61 59 $hairShade
        }
        'fair' {
            Fill $bitmap 36 38 41 39 $hair
            Fill $bitmap 55 38 61 39 $hair
            Fill $bitmap 47 36 50 38 $hairLight
            Pixel $bitmap 42 38 $hairLight; Pixel $bitmap 54 38 $hairLight
        }
        'weathered' {
            Fill $bitmap 36 38 42 40 $hair
            Fill $bitmap 56 38 61 40 $hair
            Pixel $bitmap 43 39 $hairLight
        }
        'elder' {
            Fill $bitmap 36 38 41 39 $hair
            Fill $bitmap 56 38 61 39 $hair
            Pixel $bitmap 43 37 $skin; Pixel $bitmap 54 37 $skin
            Pixel $bitmap 44 38 $skin; Pixel $bitmap 53 38 $skin
        }
    }

    # Softer, shorter brows keep the female face distinct without changing the shared resolution.
    $brow = Shade $hair 0.72
    Fill $bitmap 40 44 44 44 $brow
    Pixel $bitmap 45 45 $brow
    Fill $bitmap 52 44 56 44 $brow
    Pixel $bitmap 51 45 $brow

    # Compact almond eyes: a soft five-pixel upper lash, narrow sclera, two-pixel iris and one catchlight.
    $eyeOutline = Shade $hair 0.56
    $eyeDark = Shade $palette.Eye 0.56
    $eyeLight = C ([Math]::Min(255, $palette.Eye.R + 54)) `
                  ([Math]::Min(255, $palette.Eye.G + 54)) ([Math]::Min(255, $palette.Eye.B + 54))
    Fill $bitmap 40 47 44 47 $eyeOutline
    Pixel $bitmap 39 48 $eyeOutline
    Fill $bitmap 40 48 44 50 $sclera
    Fill $bitmap 42 48 43 50 $palette.Eye
    Pixel $bitmap 43 49 $eyeDark; Pixel $bitmap 43 50 $eyeDark
    Pixel $bitmap 42 48 $eyeLight

    Fill $bitmap 52 47 56 47 $eyeOutline
    Pixel $bitmap 57 48 $eyeOutline
    Fill $bitmap 52 48 56 50 $sclera
    Fill $bitmap 53 48 54 50 $palette.Eye
    Pixel $bitmap 53 49 $eyeDark; Pixel $bitmap 53 50 $eyeDark
    Pixel $bitmap 54 48 $eyeLight

    # The female nose is left unpainted; head lighting provides enough form without face speckles.

    # A short two-row mouth with skin-shaded corners avoids a long straight line.
    $lipSoft = C ([int](($palette.Lip.R + $skin.R * 2) / 3)) `
                 ([int](($palette.Lip.G + $skin.G * 2) / 3)) `
                 ([int](($palette.Lip.B + $skin.B * 2) / 3))
    Pixel $bitmap 46 58 $lipSoft
    Fill $bitmap 47 58 49 58 $palette.Lip
    Pixel $bitmap 50 58 $lipSoft
    Fill $bitmap 47 59 49 59 $lipSoft

    # Outer-head front: segmented fringe or narrow veil creates a raised frame around the face.
    Fill $bitmap 160 32 191 63 ([System.Drawing.Color]::Transparent)
    if ($face -in @('long', 'weathered', 'elder')) {
        $cover = if ($face -eq 'elder') { C 198 194 184 } else { C 226 218 199 }
        $coverShade = Shade $cover 0.88
        Fill $bitmap 160 32 191 37 $cover
        Fill $bitmap 160 38 164 59 $coverShade
        Fill $bitmap 188 38 191 59 $coverShade
        Fill $bitmap 165 38 168 40 $cover
        Fill $bitmap 184 38 187 40 $cover
        Pixel $bitmap 164 60 $coverShade; Pixel $bitmap 188 60 $coverShade
        Fill $bitmap 169 36 183 37 (Shade $cover 0.96)
    } else {
        Fill $bitmap 160 32 191 35 $hair
        Fill $bitmap 160 36 169 38 $hair
        Fill $bitmap 183 36 191 38 $hair
        Fill $bitmap 160 39 165 52 $hairShade
        Fill $bitmap 187 39 191 52 $hairShade
        Fill $bitmap 166 39 169 42 $hair
        Fill $bitmap 183 39 186 42 $hair
        Fill $bitmap 160 53 163 58 $hairDeep
        Fill $bitmap 189 53 191 58 $hairDeep
        Pixel $bitmap 170 37 $hairLight; Pixel $bitmap 182 37 $hairLight
    }
}

function Paint-Male-DisplayStyle($bitmap, $palette, [string]$face) {
    $skin = $palette.Skin
    $skinShade = Shade $skin 0.93
    $skinLight = C ([Math]::Min(255, $skin.R + 10)) `
                   ([Math]::Min(255, $skin.G + 8)) ([Math]::Min(255, $skin.B + 6))
    $hair = $palette.Hair
    $hairShade = Shade $hair 0.70
    $hairDeep = Shade $hair 0.48
    $hairLight = Shade $hair 1.16
    $sclera = C 238 233 220

    # Match the female 32x32 density and palette structure while retaining a more angular hairline.
    Fill $bitmap 32 32 63 63 $skin
    Fill $bitmap 32 32 63 38 $hair
    Fill $bitmap 32 39 36 46 $hair
    Fill $bitmap 60 39 63 46 $hair
    Fill $bitmap 32 47 34 54 $hairShade
    Fill $bitmap 61 47 63 54 $hairShade
    Fill $bitmap 33 55 35 60 $skinShade
    Fill $bitmap 60 55 62 60 $skinShade

    switch ($face) {
        'young' {
            Fill $bitmap 37 39 43 40 $hair
            Fill $bitmap 54 39 59 40 $hair
            Pixel $bitmap 44 39 $hairLight
        }
        'broad' {
            Fill $bitmap 36 39 45 41 $hair
            Fill $bitmap 53 39 60 41 $hair
            Fill $bitmap 32 50 35 56 $hairShade
            Fill $bitmap 60 50 63 56 $hairShade
        }
        'long' {
            Fill $bitmap 36 39 42 41 $hair
            Fill $bitmap 57 39 60 40 $hair
            Fill $bitmap 34 53 36 60 $hairShade
            Fill $bitmap 59 53 61 60 $hairShade
        }
        'fair' {
            Fill $bitmap 37 39 42 40 $hair
            Fill $bitmap 55 39 60 40 $hair
            Pixel $bitmap 48 37 $hairLight; Pixel $bitmap 49 37 $hairLight
        }
        'weathered' {
            Fill $bitmap 36 39 44 41 $hair
            Fill $bitmap 55 39 60 41 $hair
            Pixel $bitmap 45 40 $hairLight
        }
        'elder' {
            Fill $bitmap 36 39 43 40 $hair
            Fill $bitmap 56 39 60 40 $hair
            Fill $bitmap 46 37 51 38 $skin
            Pixel $bitmap 44 39 $skin; Pixel $bitmap 55 39 $skin
        }
    }

    # Brows share the same placement as female faces but are one pixel heavier and slightly straighter.
    $brow = Shade $hair 0.58
    Fill $bitmap 39 44 45 44 $brow
    Pixel $bitmap 39 45 $brow; Pixel $bitmap 45 45 $brow
    Fill $bitmap 51 44 57 44 $brow
    Pixel $bitmap 51 45 $brow; Pixel $bitmap 57 45 $brow

    # The same sclera/iris/highlight construction prevents male NPCs from reverting to dot eyes.
    $eyeOutline = Shade $hair 0.34
    $eyeDark = Shade $palette.Eye 0.50
    $eyeLight = C ([Math]::Min(255, $palette.Eye.R + 46)) `
                  ([Math]::Min(255, $palette.Eye.G + 46)) ([Math]::Min(255, $palette.Eye.B + 46))
    Fill $bitmap 39 47 45 47 $eyeOutline
    Fill $bitmap 51 47 57 47 $eyeOutline
    Pixel $bitmap 38 48 $eyeOutline; Pixel $bitmap 46 48 $eyeOutline
    Pixel $bitmap 50 48 $eyeOutline; Pixel $bitmap 58 48 $eyeOutline
    Fill $bitmap 39 48 45 50 $sclera
    Fill $bitmap 51 48 57 50 $sclera
    Fill $bitmap 42 48 44 50 $palette.Eye
    Fill $bitmap 52 48 54 50 $palette.Eye
    Pixel $bitmap 43 49 $eyeDark; Pixel $bitmap 44 49 $eyeDark
    Pixel $bitmap 52 49 $eyeDark; Pixel $bitmap 53 49 $eyeDark
    Pixel $bitmap 42 48 $eyeLight; Pixel $bitmap 54 48 $eyeLight
    Fill $bitmap 40 51 44 51 $skinShade
    Fill $bitmap 52 51 56 51 $skinShade

    # A low-contrast pixel nose matches the female material language; optional 3D geometry is kept subtle.
    Pixel $bitmap 48 53 $skinLight
    Fill $bitmap 47 54 49 55 $skinShade
    Pixel $bitmap 48 54 $skin

    $lipSoft = C ([int](($palette.Lip.R + $skin.R * 2) / 3)) `
                 ([int](($palette.Lip.G + $skin.G * 2) / 3)) `
                 ([int](($palette.Lip.B + $skin.B * 2) / 3))
    Fill $bitmap 45 59 51 59 $lipSoft
    Fill $bitmap 47 60 49 60 $skinShade

    # Segment the outer hair exactly as the female layer is segmented, without veils or braids.
    Fill $bitmap 160 32 191 63 ([System.Drawing.Color]::Transparent)
    Fill $bitmap 160 32 191 36 $hair
    Fill $bitmap 160 37 168 40 $hair
    Fill $bitmap 184 37 191 40 $hair
    Fill $bitmap 160 41 164 54 $hairShade
    Fill $bitmap 188 41 191 54 $hairShade
    Pixel $bitmap 169 38 $hairLight; Pixel $bitmap 183 38 $hairLight
    if ($face -in @('broad', 'weathered')) {
        Fill $bitmap 160 55 163 60 $hairDeep
        Fill $bitmap 189 55 191 60 $hairDeep
    }
}

$faces = [ordered]@{
    young = @{ Skin = C 205 157 113; FemaleSkin = C 218 171 136; Hair = C 70 48 31; Eye = C 62 84 77; Lip = C 137 79 66 }
    broad = @{ Skin = C 188 133 92; FemaleSkin = C 202 148 112; Hair = C 54 40 31; Eye = C 68 78 65; Lip = C 126 72 59 }
    long = @{ Skin = C 220 173 128; FemaleSkin = C 231 185 149; Hair = C 116 72 34; Eye = C 59 78 91; Lip = C 145 83 70 }
    fair = @{ Skin = C 231 187 145; FemaleSkin = C 241 199 164; Hair = C 151 105 52; Eye = C 62 91 103; Lip = C 151 87 79 }
    weathered = @{ Skin = C 174 118 82; FemaleSkin = C 190 136 102; Hair = C 90 53 31; Eye = C 68 73 60; Lip = C 111 62 53 }
    elder = @{ Skin = C 196 151 118; FemaleSkin = C 211 168 137; Hair = C 142 137 127; Eye = C 67 78 79; Lip = C 127 77 70 }
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
foreach ($gender in @('male', 'female')) {
    foreach ($entry in $faces.GetEnumerator()) {
        $palette = if ($gender -eq 'female') {
            @{ Skin = $entry.Value.FemaleSkin; Hair = $entry.Value.Hair;
               Eye = $entry.Value.Eye; Lip = $entry.Value.Lip }
        } else {
            $entry.Value
        }
        $logical = [System.Drawing.Bitmap]::new(64, 32,
                [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        Paint-Head $logical $palette $gender $entry.Key
        $scale = 4
        $bitmap = [System.Drawing.Bitmap]::new((64 * $scale), (32 * $scale),
                [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
        $scaleGraphics = [System.Drawing.Graphics]::FromImage($bitmap)
        $scaleGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
        $scaleGraphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
        $scaleGraphics.DrawImage($logical, 0, 0, (64 * $scale), (32 * $scale))
        $scaleGraphics.Dispose()
        $logical.Dispose()
        if ($gender -eq 'female') {
            Paint-Female-DisplayStyle $bitmap $palette $entry.Key
        } else {
            Paint-Male-DisplayStyle $bitmap $palette $entry.Key
        }
        $path = Join-Path $OutputDirectory ($gender + '_' + $entry.Key + '.png')
        $bitmap.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
        $bitmap.Dispose()
    }
}

# Produce the enlarged art-review sheet by compositing base face and outer hair or veil.
$preview = [System.Drawing.Bitmap]::new(1008, 190,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$graphics = [System.Drawing.Graphics]::FromImage($preview)
$graphics.Clear((C 226 216 194))
$graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$font = [System.Drawing.Font]::new('Segoe UI', 13, [System.Drawing.FontStyle]::Bold)
$brush = [System.Drawing.SolidBrush]::new((C 62 49 38))
$pen = [System.Drawing.Pen]::new((C 91 72 53), 3)
$index = 0
foreach ($face in $faces.Keys) {
    $source = [System.Drawing.Bitmap]::FromFile((Join-Path $OutputDirectory ('female_' + $face + '.png')))
    $left = 20 + $index * 164
    $destination = [System.Drawing.Rectangle]::new($left, 18, 128, 128)
    $graphics.DrawImage($source, $destination, 32, 32, 32, 32, [System.Drawing.GraphicsUnit]::Pixel)
    $graphics.DrawImage($source, $destination, 160, 32, 32, 32, [System.Drawing.GraphicsUnit]::Pixel)
    $graphics.DrawRectangle($pen, $destination)
    $graphics.DrawString($face, $font, $brush, [single]$left, [single]152)
    $source.Dispose()
    $index++
}
New-Item -ItemType Directory -Path (Split-Path $PreviewPath) -Force | Out-Null
$preview.Save($PreviewPath, [System.Drawing.Imaging.ImageFormat]::Png)
$pen.Dispose(); $brush.Dispose(); $font.Dispose(); $graphics.Dispose(); $preview.Dispose()

# Two-row comparison proves that both genders use the same effective density and facial construction.
$comparison = [System.Drawing.Bitmap]::new(1008, 344,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$comparisonGraphics = [System.Drawing.Graphics]::FromImage($comparison)
$comparisonGraphics.Clear((C 226 216 194))
$comparisonGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$comparisonGraphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$comparisonFont = [System.Drawing.Font]::new('Segoe UI', 12, [System.Drawing.FontStyle]::Bold)
$comparisonBrush = [System.Drawing.SolidBrush]::new((C 62 49 38))
$comparisonPen = [System.Drawing.Pen]::new((C 91 72 53), 2)
$row = 0
foreach ($gender in @('male', 'female')) {
    $index = 0
    foreach ($face in $faces.Keys) {
        $source = [System.Drawing.Bitmap]::FromFile((Join-Path $OutputDirectory ($gender + '_' + $face + '.png')))
        $left = 20 + $index * 164
        $top = 12 + $row * 166
        $destination = [System.Drawing.Rectangle]::new($left, $top, 128, 128)
        $comparisonGraphics.DrawImage($source, $destination, 32, 32, 32, 32, [System.Drawing.GraphicsUnit]::Pixel)
        $comparisonGraphics.DrawImage($source, $destination, 160, 32, 32, 32, [System.Drawing.GraphicsUnit]::Pixel)
        $comparisonGraphics.DrawRectangle($comparisonPen, $destination)
        $comparisonGraphics.DrawString(($gender + ' / ' + $face), $comparisonFont, $comparisonBrush,
                [single]$left, [single]($top + 132))
        $source.Dispose()
        $index++
    }
    $row++
}
New-Item -ItemType Directory -Path (Split-Path $ComparisonPreviewPath) -Force | Out-Null
$comparison.Save($ComparisonPreviewPath, [System.Drawing.Imaging.ImageFormat]::Png)
$comparisonPen.Dispose(); $comparisonBrush.Dispose(); $comparisonFont.Dispose()
$comparisonGraphics.Dispose(); $comparison.Dispose()

Write-Host "Generated 12 NPC face textures: $OutputDirectory"
Write-Host "Female face review sheet: $PreviewPath"
Write-Host "Male/female style comparison: $ComparisonPreviewPath"
