$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\scripts\module-package.ps1')
$testDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('cts-package-test-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $testDirectory | Out-Null
try {
    $source = Join-Path $testDirectory 'source.sh'
    $normalized = Join-Path $testDirectory 'normalized.sh'
    foreach ($lineBreak in @("`n", "`r`n", "`r")) {
        foreach ($withBom in @($false, $true)) {
            [System.IO.File]::WriteAllText($source, "#!/system/bin/sh${lineBreak}exit 0${lineBreak}",
                [System.Text.UTF8Encoding]::new($withBom))
            Write-ModuleTextFile $source $normalized
            $bytes = [System.IO.File]::ReadAllBytes($normalized)
            if ([System.Text.Encoding]::UTF8.GetString($bytes) -cne "#!/system/bin/sh`nexit 0`n") {
                throw 'Module text normalization failed'
            }
        }
    }
    $valid = @{
        'customize.sh' = [System.Text.Encoding]::UTF8.GetBytes("#!/system/bin/sh`nexit 0`n")
        'module.prop' = [System.Text.Encoding]::UTF8.GetBytes("id=test_module`n")
        'helper.dex' = [byte[]]@(1)
        'zygisk/arm64-v8a.so' = [byte[]]@(1)
        'skip_mount' = [byte[]]@()
    }
    function Test-Case {
        param([hashtable]$Entries, [string]$ExpectedError = '')
        $zipPath = Join-Path $testDirectory ([guid]::NewGuid().ToString() + '.zip')
        $archive = [System.IO.Compression.ZipFile]::Open($zipPath, 'Create')
        try {
            foreach ($name in $Entries.Keys) {
                $stream = $archive.CreateEntry($name).Open()
                try { $stream.Write($Entries[$name], 0, $Entries[$name].Length) } finally { $stream.Dispose() }
            }
        } finally { $archive.Dispose() }
        $actualError = ''
        try { Assert-ModulePackage $zipPath } catch { $actualError = $_.Exception.Message }
        if ($ExpectedError -eq '') {
            if ($actualError) { throw $actualError }
        } elseif (-not $actualError.Contains($ExpectedError)) {
            throw "Expected '$ExpectedError', got '$actualError'"
        }
    }
    Test-Case $valid
    foreach ($name in @('customize.sh', 'module.prop')) {
        $bad = $valid.Clone()
        $bad[$name] = [System.Text.Encoding]::UTF8.GetBytes("bad`r`n")
        Test-Case $bad "$name contains CR"
        $bad[$name] = [byte[]](@(0xef, 0xbb, 0xbf) + $valid[$name])
        Test-Case $bad "$name contains a UTF-8 BOM"
    }
    $bad = $valid.Clone()
    $bad.Remove('helper.dex')
    Test-Case $bad 'Missing helper.dex'
    $bad = $valid.Clone()
    $bad['zygisk/arm64-v8a.so'] = [byte[]]@()
    Test-Case $bad 'Empty zygisk/arm64-v8a.so'
    Write-Host 'PASS: LF/CRLF/CR and BOM normalization; valid ZIP; CR/BOM/missing/empty ZIP rejection'
} finally {
    # Only delete this test's uniquely allocated temporary directory.
    $resolvedTestDirectory = (Resolve-Path -LiteralPath $testDirectory).Path
    if ($resolvedTestDirectory -ne [System.IO.Path]::GetFullPath($testDirectory) -or
        (Split-Path -Leaf $resolvedTestDirectory) -notlike 'cts-package-test-*') {
        throw 'Unexpected test cleanup path'
    }
    Remove-Item -LiteralPath $resolvedTestDirectory -Recurse -Force
}
