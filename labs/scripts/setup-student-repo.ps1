<#
.SYNOPSIS
    GH-200 學員環境快速設定（Windows / PowerShell）

.DESCRIPTION
    這個腳本會：
      1. 檢查前置工具：git / gh / java (>= 21)
      2. 檢查 gh 是否已登入
      3. 把課程 repo fork 到你自己的帳號（已存在就跳過），並 clone 到本機
      4. 在你的 fork 上啟用 GitHub Actions
      5. 在你的 fork 上建立 test 與 production 兩個 environment
      6. （選用）設定 VM SSH 部署用的「非機密」variables
      7. 印出下一步

    安全性：
      - 不刪除任何檔案、不使用萬用字元
      - 不會覆蓋既有的 clone 目錄
      - 不硬編任何 token，一律透過 gh 的既有登入狀態
      - 不接收私鑰參數：SSH 私鑰是機密，請由講師以
        `Get-Content -Raw -LiteralPath id_deploy | gh secret set VM_SSH_PRIVATE_KEY --env test --repo <repo>`
        （並對 production 各設一份 Environment secret，不是 repository secret）另外設定

.EXAMPLE
    .\setup-student-repo.ps1

.EXAMPLE
    .\setup-student-repo.ps1 -TargetDir C:\GH200 -VmPublicIp <VM_PUBLIC_IP> `
        -VmSshUser azureuser -VmSshHostKey "<known_hosts 條目>"
#>
[CmdletBinding()]
param(
    # 課程 repo（講師提供）
    [string] $UpstreamRepo = 'MoneyDemo/20260903-GH200',

    # clone 到哪個父目錄底下
    [string] $TargetDir = (Join-Path $HOME 'gh200'),

    # 以下皆為選用，講師在課堂上公布實際值後再填（皆為非機密）
    [string] $VmPublicIp = '',
    [string] $VmSshUser = '',
    [string] $VmSshHostKey = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Write-Step { param([string] $Text) Write-Host "`n==> $Text" -ForegroundColor Cyan }
function Write-Ok   { param([string] $Text) Write-Host "    [OK] $Text" -ForegroundColor Green }
function Write-Warn { param([string] $Text) Write-Host "    [!]  $Text" -ForegroundColor Yellow }

# ---------------------------------------------------------------------------
# 1. 前置工具檢查
# ---------------------------------------------------------------------------
Write-Step '檢查前置工具'

foreach ($tool in @('git', 'gh', 'java')) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "找不到 '$tool'。請先安裝後再執行本腳本（gh = GitHub CLI）。"
    }
    Write-Ok "$tool 已安裝"
}

# java -version 會輸出到 stderr，需要合併後再判讀
$javaRaw = (& java -version 2>&1 | Out-String)
if ($javaRaw -notmatch 'version\s+"?(\d+)') {
    throw "無法判讀 java 版本，輸出如下：`n$javaRaw"
}
$javaMajor = [int]$Matches[1]
if ($javaMajor -lt 21) {
    throw "需要 Java 21 或以上，目前偵測到 $javaMajor。請安裝 JDK 21（建議 Temurin）。"
}
Write-Ok "Java $javaMajor（>= 21）"

Write-Host "    注意：本課程使用 Maven Wrapper，不需要安裝 Maven。" -ForegroundColor DarkGray
Write-Host "          本機建置指令為 .\mvnw.cmd -B verify" -ForegroundColor DarkGray

# ---------------------------------------------------------------------------
# 2. gh 登入狀態
# ---------------------------------------------------------------------------
Write-Step '檢查 GitHub CLI 登入狀態'

& gh auth status
if ($LASTEXITCODE -ne 0) {
    throw "gh 尚未登入。請先執行： gh auth login"
}
Write-Ok 'gh 已登入'

$me = (& gh api user --jq '.login').Trim()
if ([string]::IsNullOrWhiteSpace($me)) { throw '無法取得 GitHub 帳號名稱。' }
Write-Ok "GitHub 帳號：$me"

$repoName = $UpstreamRepo.Split('/')[-1]
$myRepo = "$me/$repoName"

# ---------------------------------------------------------------------------
# 3. Fork（已存在則沿用）
# ---------------------------------------------------------------------------
Write-Step "準備你自己的 repo：$myRepo"

& gh repo view $myRepo --json name 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    $repoIdentity = & gh repo view $myRepo --json isFork,parent | ConvertFrom-Json
    if (-not $repoIdentity.isFork -or $repoIdentity.parent.nameWithOwner -ne $UpstreamRepo) {
        throw "$myRepo 已存在，但不是 $UpstreamRepo 的 fork；為避免修改無關 repo，腳本停止。"
    }
    Write-Ok "$myRepo 已存在且已確認是 $UpstreamRepo 的 fork"
}
else {
    Write-Host "    fork $UpstreamRepo ..."
    & gh repo fork $UpstreamRepo --clone=false --remote=false
    if ($LASTEXITCODE -ne 0) { throw "fork 失敗，請確認你對 $UpstreamRepo 有讀取權限。" }

    # fork 為非同步作業，等它出現
    $ready = $false
    foreach ($i in 1..15) {
        Start-Sleep -Seconds 2
        & gh repo view $myRepo --json name 2>&1 | Out-Null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        Write-Host "    等待 fork 完成... ($i/15)"
    }
    if (-not $ready) { throw "fork 似乎尚未完成，請稍後再執行一次本腳本。" }
    Write-Ok "已建立 $myRepo"
}

# ---------------------------------------------------------------------------
# 4. Clone
# ---------------------------------------------------------------------------
Write-Step 'Clone 到本機'

if (-not (Test-Path -LiteralPath $TargetDir)) {
    New-Item -ItemType Directory -Path $TargetDir -Force | Out-Null
}
$clonePath = Join-Path $TargetDir $repoName

if (Test-Path -LiteralPath $clonePath) {
    Write-Warn "$clonePath 已存在，跳過 clone（腳本不會覆寫既有目錄）"
}
else {
    & gh repo clone $myRepo $clonePath
    if ($LASTEXITCODE -ne 0) { throw 'clone 失敗。' }
    Write-Ok "已 clone 到 $clonePath"
}

# ---------------------------------------------------------------------------
# 5. 啟用 Actions
# ---------------------------------------------------------------------------
Write-Step '啟用 GitHub Actions'

& gh api --method PUT "repos/$myRepo/actions/permissions" `
    -H 'Accept: application/vnd.github+json' `
    -F enabled=true | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Actions 啟用失敗，請確認你對該 repo 有 admin 權限。' }
Write-Ok 'Actions 已啟用（保留既有 allowed-actions policy）'
Write-Host "    註：SSH 部署不需要 public repo，你的 fork 可維持 private。" -ForegroundColor DarkGray

# ---------------------------------------------------------------------------
# 6. 建立 environments
# ---------------------------------------------------------------------------
Write-Step '建立 environments：test / production'

foreach ($envName in @('test', 'production')) {
    & gh api --method PUT "repos/$myRepo/environments/$envName" `
        -H 'Accept: application/vnd.github+json' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "建立 environment '$envName' 失敗。" }
    Write-Ok "environment '$envName' 就緒"
}
Write-Warn "production 的 required reviewer 需要你自己在 repo 設定頁加上（Lab 05 會用到）"

# ---------------------------------------------------------------------------
# 7. 選用：非機密 variables（沒填的會跳過）
# ---------------------------------------------------------------------------
Write-Step '設定 variables（沒填的會跳過）'

function Set-RepoVariable {
    param([string] $Name, [string] $Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        Write-Warn "variable $Name 未提供，跳過"
        return
    }
    & gh variable set $Name --repo $myRepo --body $Value
    if ($LASTEXITCODE -ne 0) { throw "設定 variable $Name 失敗。" }
    Write-Ok "variable $Name 已設定"
}

Set-RepoVariable -Name 'VM_PUBLIC_IP'   -Value $VmPublicIp
Set-RepoVariable -Name 'VM_SSH_USER'    -Value $VmSshUser
Set-RepoVariable -Name 'VM_SSH_HOST_KEY' -Value $VmSshHostKey

Write-Warn 'SSH 私鑰是機密，本腳本不接收私鑰參數。請由講師設成 test/production 的 Environment secret（不是 repository secret）：'
Write-Warn "    Get-Content -Raw -LiteralPath id_deploy | gh secret set VM_SSH_PRIVATE_KEY --env test --repo $myRepo"
Write-Warn "    Get-Content -Raw -LiteralPath id_deploy | gh secret set VM_SSH_PRIVATE_KEY --env production --repo $myRepo"

# ---------------------------------------------------------------------------
# 8. 下一步
# ---------------------------------------------------------------------------
Write-Step '完成！接下來要做的事'

Write-Host @"
  你的 repo   : https://github.com/$myRepo
  本機路徑    : $clonePath

  1. cd "$clonePath"
  2. 本機先試跑一次建置（不需要安裝 Maven）：
         .\mvnw.cmd -B verify
     成功後應該會產生 target\simpleweb.jar
  3. 打開 labs\README.md，從 Lab 01 開始
  4. 講師確認你的 fork 已配置好對應的 VM SSH 存取後，才設定部署用的 variables：
         gh variable set VM_PUBLIC_IP    --repo $myRepo --body "<VM_PUBLIC_IP>"
         gh variable set VM_SSH_USER     --repo $myRepo --body "<USER>"
         gh variable set VM_SSH_HOST_KEY --repo $myRepo --body "<known_hosts 條目>"
     私鑰請由講師設成 test/production 的 Environment secret（不要放進指令列參數）：
         Get-Content -Raw -LiteralPath id_deploy | gh secret set VM_SSH_PRIVATE_KEY --env test --repo $myRepo
         Get-Content -Raw -LiteralPath id_deploy | gh secret set VM_SSH_PRIVATE_KEY --env production --repo $myRepo
     （兩個 Environment 都設好、default branch 的 04/05/06 實跑成功後，才由講師另外刪除舊的 repo 層級 secret）
  5. Lab 05 之前，記得到 repo 設定 > Environments > production
     加上 required reviewer（把你自己加進去即可）
  6. Lab 04／05 預設由講師在 class repo 實跑；fork 若沒有專屬的部署金鑰，
     你仍要完成 YAML，但不要嘗試共用講師的私鑰。
"@ -ForegroundColor White
