# Row-by-row "ink profile" of a rectangle of a screenshot.
#
# The S12 observable. The §26 lift is written through MultiTranslateDelegate, so it never appears
# in a dumpsys layout bound -- which is exactly why the defect was missed. Pixels are the only
# place the DRAWN position exists. For each screen row of the rect this counts pixels that look
# like app-icon artwork (saturated and bright) rather than the folder's flat background or the
# greyscale frost/x badge, and reports the weighted vertical centroid of that ink.
#
# usage: rowink.ps1 -Png <file> -L <l> -T <t> -R <r> -B <b> [-MinSat 0.30] [-MinVal 0.25] [-Profile]
param(
  [Parameter(Mandatory=$true)][string]$Png,
  [Parameter(Mandatory=$true)][int]$L,
  [Parameter(Mandatory=$true)][int]$T,
  [Parameter(Mandatory=$true)][int]$R,
  [Parameter(Mandatory=$true)][int]$B,
  [double]$MinSat = 0.30,
  [double]$MinVal = 0.25,
  [switch]$Profile
)
Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile((Resolve-Path $Png))
try {
  $L = [Math]::Max(0, $L); $T = [Math]::Max(0, $T)
  $R = [Math]::Min($bmp.Width, $R); $B = [Math]::Min($bmp.Height, $B)
  $rect = New-Object System.Drawing.Rectangle 0, 0, $bmp.Width, $bmp.Height
  $data = $bmp.LockBits($rect, [System.Drawing.Imaging.ImageLockMode]::ReadOnly,
                        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $stride = $data.Stride
  $buf = New-Object byte[] ($stride * $bmp.Height)
  [System.Runtime.InteropServices.Marshal]::Copy($data.Scan0, $buf, 0, $buf.Length)
  $bmp.UnlockBits($data)

  $total = 0.0; $wsum = 0.0; $first = -1; $last = -1
  $out = New-Object System.Collections.Generic.List[string]
  for ($y = $T; $y -lt $B; $y++) {
    $row = $y * $stride
    $c = 0
    for ($x = $L; $x -lt $R; $x++) {
      $o = $row + $x * 4           # BGRA
      $b8 = $buf[$o]; $g8 = $buf[$o+1]; $r8 = $buf[$o+2]
      $mx = [Math]::Max($r8, [Math]::Max($g8, $b8))
      $mn = [Math]::Min($r8, [Math]::Min($g8, $b8))
      if ($mx -eq 0) { continue }
      $sat = ($mx - $mn) / $mx     # HSV saturation
      $val = $mx / 255.0
      if ($sat -ge $MinSat -and $val -ge $MinVal) { $c++ }
    }
    if ($c -gt 0) { if ($first -lt 0) { $first = $y }; $last = $y }
    $total += $c; $wsum += ($c * $y)
    if ($Profile) { $out.Add("$y $c") }
  }
  if ($Profile) { $out | ForEach-Object { $_ } }
  $cent = if ($total -gt 0) { [Math]::Round($wsum / $total, 2) } else { -1 }
  "rect=$L,$T-$R,$B inkpx=$([int]$total) firstRow=$first lastRow=$last centroidY=$cent"
} finally { $bmp.Dispose() }
