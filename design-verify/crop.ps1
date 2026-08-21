# Crop a rect out of a screenshot, optionally scaled, so a region can be eyeballed at size.
# usage: crop.ps1 -Png in.png -Out out.png -L 610 -T 1562 -R 1054 -B 2220 [-Scale 2]
param(
  [Parameter(Mandatory=$true)][string]$Png,
  [Parameter(Mandatory=$true)][string]$Out,
  [Parameter(Mandatory=$true)][int]$L,
  [Parameter(Mandatory=$true)][int]$T,
  [Parameter(Mandatory=$true)][int]$R,
  [Parameter(Mandatory=$true)][int]$B,
  [double]$Scale = 1
)
Add-Type -AssemblyName System.Drawing
$src = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Png))
try {
  $w = $R - $L; $h = $B - $T
  $dw = [int]($w * $Scale); $dh = [int]($h * $Scale)
  $dst = New-Object System.Drawing.Bitmap $dw, $dh
  $g = [System.Drawing.Graphics]::FromImage($dst)
  $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
  $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
  $g.DrawImage($src, (New-Object System.Drawing.Rectangle 0,0,$dw,$dh),
                     (New-Object System.Drawing.Rectangle $L,$T,$w,$h),
                     [System.Drawing.GraphicsUnit]::Pixel)
  $g.Dispose()
  $dst.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
  $dst.Dispose()
  "cropped $L,$T-$R,$B -> $Out (${dw}x${dh})"
} finally { $src.Dispose() }
