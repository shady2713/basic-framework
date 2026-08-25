[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $Gate
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepoRoot = Split-Path -Parent $PSScriptRoot

function Show-Usage {
    @'
Usage: & .\.harness\verify.ps1 <gate>

Gates:
  contracts    field catalog, data lifecycle, and exception expiry
  lockfile     frozen frontend dependency graph
  backend      backend compile, unit tests, formatting, coverage, architecture
  frontend     dependency/type/spelling checks, lint, and coverage tests
  integration  Docker-backed MySQL/Redis/Flyway and packaged-jar boot smoke
  verify       contracts + backend + frontend
  all          lockfile + verify + integration
'@ | Write-Output
}

function Invoke-External {
    param(
        [Parameter(Mandatory = $true)]
        [string] $FilePath,
        [Parameter()]
        [string[]] $Arguments = @()
    )

    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "harness: '$FilePath' exited with code $LASTEXITCODE"
    }
}

function Invoke-InDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [Parameter(Mandatory = $true)]
        [scriptblock] $Action
    )

    Push-Location -LiteralPath $Path
    try {
        & $Action
    } finally {
        Pop-Location
    }
}

function Invoke-ContractsGate {
    Invoke-InDirectory $RepoRoot {
        Invoke-External 'node' @('scripts/check-field-catalog.mjs')
        Invoke-External 'node' @('scripts/check-data-lifecycle.mjs')
        Invoke-External 'node' @('scripts/check-exceptions.mjs')
    }
}

function Invoke-LockfileGate {
    Invoke-InDirectory (Join-Path $RepoRoot '前端代码/basic-framework-admin') {
        Invoke-External 'pnpm.cmd' @('install', '--frozen-lockfile', '--ignore-scripts')
    }
}

function Invoke-BackendGate {
    Invoke-InDirectory (Join-Path $RepoRoot '后端代码/basic-framework-boot') {
        Invoke-External '.\mvnw.cmd' @('-q', 'verify')
    }
}

function Invoke-FrontendGate {
    Invoke-InDirectory (Join-Path $RepoRoot '前端代码/basic-framework-admin') {
        Invoke-External 'pnpm.cmd' @('check')
        Invoke-External 'pnpm.cmd' @('lint')
        Invoke-External 'pnpm.cmd' @('test:coverage')
    }
}

function Invoke-IntegrationGate {
    $docker = Get-Command 'docker' -ErrorAction SilentlyContinue
    if ($null -ne $docker) {
        Invoke-External 'docker' @('info', '--format', 'Docker server {{.ServerVersion}}')
    } elseif ([string]::IsNullOrWhiteSpace($env:DOCKER_HOST)) {
        throw 'harness: integration requires a Docker daemon'
    } else {
        Write-Output "harness: Docker CLI unavailable; Testcontainers will verify $env:DOCKER_HOST"
    }

    Invoke-InDirectory (Join-Path $RepoRoot '后端代码/basic-framework-boot') {
        Invoke-External '.\mvnw.cmd' @('-q', '-Pintegration', 'verify')
    }
}

function Invoke-HarnessGate {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Name
    )

    Write-Output "== harness:$Name =="
    switch ($Name) {
        'contracts' { Invoke-ContractsGate }
        'lockfile' { Invoke-LockfileGate }
        'backend' { Invoke-BackendGate }
        'frontend' { Invoke-FrontendGate }
        'integration' { Invoke-IntegrationGate }
        'verify' {
            Invoke-HarnessGate 'contracts'
            Invoke-HarnessGate 'backend'
            Invoke-HarnessGate 'frontend'
        }
        'all' {
            Invoke-HarnessGate 'lockfile'
            Invoke-HarnessGate 'verify'
            Invoke-HarnessGate 'integration'
        }
        { $_ -in @('--list', '-h', '--help') } { Show-Usage }
        default {
            Show-Usage
            throw "harness: unknown gate '$Name'"
        }
    }
}

Invoke-HarnessGate $Gate
