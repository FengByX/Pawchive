# Pawchive local quality gates one-shot verification (ARCH-014)
# Usage: powershell -ExecutionPolicy Bypass -File .\quality-check.ps1 [--fast]
#   --fast  run unit tests + lint only (skip coverage & dependency report)
# Gates:
#   1. JVM unit tests (testDebugUnitTest, all core/data test cases)
#   2. Android Lint (lintDebug, error level fails the build, abortOnError=true by default)
#   3. Kover coverage report (merged HTML, focused on core+data business layer)
#   4. Kover coverage gate (core+data line coverage minimum, see build.gradle.kts)
#   5. Dependency scan (writes debug runtime dependency tree for manual review)
param(
    [switch]$Fast
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$failures = @()

function Invoke-Step {
    param([string]$Name, [string[]]$CmdArgs)
    Write-Host ""
    Write-Host "=== [$Name] ===" -ForegroundColor Cyan
    & .\gradlew.bat @CmdArgs --console=plain
    if ($LASTEXITCODE -ne 0) {
        $script:failures += $Name
        Write-Host "[$Name] FAILED (exit $LASTEXITCODE)" -ForegroundColor Red
    } else {
        Write-Host "[$Name] PASSED" -ForegroundColor Green
    }
}

# 1. JVM unit tests
Invoke-Step "Unit Tests" @("testDebugUnitTest")

# 2. Android Lint gate
Invoke-Step "Lint" @("lintDebug")

if (-not $Fast) {
    # 3. Coverage report (merged HTML)
    Invoke-Step "Coverage Report" @("koverHtmlReport")

    # 4. Coverage gate
    Invoke-Step "Coverage Verify" @("koverVerify")

    # 5. Dependency scan (Dependabot handles automated upgrade PRs)
    Write-Host ""
    Write-Host "=== [Dependency Report] ===" -ForegroundColor Cyan
    & .\gradlew.bat ":app:dependencies" "--configuration" "debugRuntimeClasspath" "--console=plain" 2>&1 | Out-File -FilePath "build/dependency-report.txt" -Encoding utf8
    Write-Host "Dependency tree written to build/dependency-report.txt" -ForegroundColor Yellow
}

Write-Host ""
if ($failures.Count -gt 0) {
    Write-Host "Quality gates FAILED: $($failures -join ', ')" -ForegroundColor Red
    exit 1
} else {
    Write-Host "All quality gates PASSED" -ForegroundColor Green
    exit 0
}
