# Android module text must not inherit the Windows checkout's line endings.
function Write-ModuleTextFile {
    param([string]$Source, [string]$Destination)
    $content = [System.IO.File]::ReadAllText($Source)
    $content = $content.Replace("`r`n", "`n").Replace("`r", "`n")
    [System.IO.File]::WriteAllText($Destination, $content, [System.Text.UTF8Encoding]::new($false))
}

function Assert-ModulePackage {
    param([string]$ZipPath)
    $archive = [System.IO.Compression.ZipFile]::OpenRead($ZipPath)
    try {
        foreach ($name in @('module.prop', 'helper.dex', 'customize.sh', 'skip_mount', 'zygisk/arm64-v8a.so')) {
            $entry = $archive.GetEntry($name)
            if ($null -eq $entry) { throw "Missing $name in module ZIP" }
            if ($name -ne 'skip_mount' -and $entry.Length -le 0) { throw "Empty $name in module ZIP" }
        }
        foreach ($name in @('customize.sh', 'module.prop')) {
            $stream = $archive.GetEntry($name).Open()
            $buffer = [System.IO.MemoryStream]::new()
            try {
                $stream.CopyTo($buffer)
                $bytes = $buffer.ToArray()
            } finally {
                $stream.Dispose()
                $buffer.Dispose()
            }
            if ($bytes -contains [byte]13) { throw "$name contains CR line endings; Android requires LF" }
            if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xef -and $bytes[1] -eq 0xbb -and $bytes[2] -eq 0xbf) {
                throw "$name contains a UTF-8 BOM"
            }
            $text = [System.Text.UTF8Encoding]::new($false, $true).GetString($bytes)
            if ($text.Contains([char]0) -or -not $text.EndsWith("`n")) {
                throw "$name must be UTF-8 text with a final LF"
            }
        }
    } finally {
        $archive.Dispose()
    }
}
