Add-Type -AssemblyName System.Drawing

$pairs = @( @('mdpi',48), @('hdpi',72), @('xhdpi',96), @('xxhdpi',144), @('xxxhdpi',192) )

foreach ($pair in $pairs) {
    $k = $pair[0]
    $n = [int]$pair[1]
    $dir = 'D:\Tools\autotask\app\res\mipmap-' + $k
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir | Out-Null }
    $out = Join-Path $dir 'ic_launcher.png'

    $bmp = New-Object System.Drawing.Bitmap($n, $n)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)

    $green = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 22, 170, 90))
    $white = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)

    $w0 = [float]($n - 1)
    $g.FillEllipse($green, [float]0, [float]0, $w0, $w0)

    $cx = [float]($n / 2.0)
    $cy = [float]($n / 2.0)
    $r  = [float]($n * 0.30)
    $lw = [float]([Math]::Max(2.0, $n * 0.055))

    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White), $lw
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round

    $g.DrawEllipse($pen, [float]($cx - $r), [float]($cy - $r), [float]($r * 2), [float]($r * 2))

    $br = [float]($n * 0.085)
    $bx1 = [float]($cx - $r * 0.62)
    $by1 = [float]($cy - $r * 0.90)
    $g.FillEllipse($white, [float]($bx1 - $br), [float]($by1 - $br), [float]($br * 2), [float]($br * 2))
    $bx2 = [float]($cx + $r * 0.62)
    $g.FillEllipse($white, [float]($bx2 - $br), [float]($by1 - $br), [float]($br * 2), [float]($br * 2))

    $fr = [float]($n * 0.070)
    $fx1 = [float]($cx - $r * 0.72)
    $fy1 = [float]($cy + $r * 1.02)
    $g.FillEllipse($white, [float]($fx1 - $fr), [float]($fy1 - $fr), [float]($fr * 2), [float]($fr * 2))
    $fx2 = [float]($cx + $r * 0.72)
    $g.FillEllipse($white, [float]($fx2 - $fr), [float]($fy1 - $fr), [float]($fr * 2), [float]($fr * 2))

    $g.DrawLine($pen, $cx, $cy, $cx, [float]($cy - $r * 0.60))
    $ang = [Math]::PI / 3.0
    $ex = [float]($cx + [Math]::Sin($ang) * $r * 0.68)
    $ey = [float]($cy + [Math]::Cos($ang) * $r * 0.68)
    $g.DrawLine($pen, $cx, $cy, $ex, $ey)

    $cr = [float]([Math]::Max(1.5, $n * 0.030))
    $g.FillEllipse($white, [float]($cx - $cr), [float]($cy - $cr), [float]($cr * 2), [float]($cr * 2))

    $bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
    $pen.Dispose()
    $green.Dispose()
    $white.Dispose()
    "OK $k $n"
}
