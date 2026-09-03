# GH-200 GitHub Actions — 學員實作手冊

> 課程 repo：[`MoneyDemo/20260903-GH200`](https://github.com/MoneyDemo/20260903-GH200)
> 對象：**沒有用過 GitHub Actions 的企業 Java 開發者**

## 這份手冊要帶你達成什麼

> **訓練完能自己寫 YAML，完成 Build → Test → Package → Deploy 到測試環境／正式環境，並能查看 workflow log 進行錯誤處理。**

因此這份手冊有一個刻意的設計：**所有 lab 的正文都不會給你可以直接複製貼上的完整 YAML。**
每個 lab 都提供一份 `starters/lab0X.yml` 骨架，裡面用 `# TODO:` 標出你要自己補的部分。
完整答案放在 `solutions/`，卡住時再看——但請先自己動手打過一次。**打字的過程就是學習本身。**

## Lab 一覽

| Lab | 主題 | 對應客戶模組 | 預估時間 |
|---|---|---|---|
| [Lab 01](lab01-first-workflow.md) | 第一個 workflow：觸發事件、job、step | **M1** Design and Manage Workflows | 20 分 |
| [Lab 02](lab02-build-and-test.md) | Build & Test：checkout、setup-java、`./mvnw -B verify`、Job Summary | **M1** + **M2** | 30 分 |
| [Lab 03](lab03-package-artifact.md) | 兩個 job + `needs:` + artifact 交接（artifact vs cache 概念） | **M1** | 30 分 |
| [Lab 04](lab04-deploy-test.md) | 部署到 **test**：environment、`upload`/`download-artifact`、SSH 部署到 on-prem VM、釘選主機指紋 | **M1** + **M5** | 45 分 |
| [Lab 05](lab05-prod-approval.md) | Promote 到 **production**：核准關卡、保護規則、build once/deploy many | **M5** Secure and Optimize Automation | 30 分 |
| [Lab 06](lab06-troubleshooting.md) | **讀 log 除錯**：四個壞掉的 workflow、debug logging、re-run failed jobs | **M2** Consume and Troubleshoot Workflows | 45 分 |
| [Lab 07](lab07-selfhosted-runner.md) | （選修）self-hosted runner、label、runner group、組織政策 | **M4** Manage GitHub Actions in the Enterprise | 30 分 |

> 本次交付沒有 Module 3。
> Lab 06 是整份手冊最重要的一個——它直接對應「能查看 workflow log 進行錯誤處理」這個目標。

### 本課程**不使用**的功能

為了讓初學者專注在主線上，以下功能本次**不會**練習，也不需要出現在你的作業中：

- Reusable workflows
- Matrix strategy
- Cache（**只講概念**，見 Lab 03 的比較表；不做練習）

## 你要部署的應用程式

一個 Spring Boot 應用，Maven 專案位於 repo 根目錄。

| 項目 | 值 |
|---|---|
| Java | **21** |
| Spring Boot | **4.1.1** |
| 建置指令 | `./mvnw -B verify`（Windows 本機：`.\mvnw.cmd -B verify`） |
| 建置產出 | **`target/simpleweb.jar`**（固定，永遠是這個檔名） |
| 端點 | `/`（HTML，顯示 environment / build SHA / hostname）、`/api/info`（JSON）、`/actuator/health` |
| 執行期環境變數 | systemd unit 直接設定 `SERVER_PORT` / `APP_ENVIRONMENT`；build metadata（build SHA／時間）在 **build 時烤進 jar**，不經環境變數 |

> **Maven Wrapper 已經 commit 在 repo 裡，你的機器不需要安裝 Maven。**
> 但你**需要** JDK 21。

## 目標環境

一台 Azure **Ubuntu 24.04** VM，上面跑兩個 systemd 服務：

| 環境 | systemd service | Port | Jar 路徑 | GitHub Environment |
|---|---|---|---|---|
| 測試 | `simpleweb-test` | **8080** | `/opt/simpleweb/test/simpleweb.jar` | `test` |
| 正式 | `simpleweb-prod` | **8081** | `/opt/simpleweb/prod/simpleweb.jar` | `production`（有**核准關卡**） |

systemd unit 以 `Environment=` 固定 `SERVER_PORT` / `APP_ENVIRONMENT`。
build metadata（build SHA／時間）**不是**執行期設定：它在 build 時由 Maven resource
filtering 烤進 jar，由應用程式直接從 classpath 讀出，所以部署端**不需要、也不應該**
再寫任何 `APP_BUILD_*` 檔案。

部署方式：GitHub-hosted runner 用 **SSH（`scp` + `ssh`）** 把 jar 推到 VM 對應目錄，
再重啟 systemd 服務。VM 在這裡模擬 on-prem server。

### 交付路徑與信任邊界

build job 會把 `target/simpleweb.jar` 上傳成 **workflow artifact**（`upload-artifact`），
deploy job 再用 `download-artifact` 取回同一份 jar 推上 VM。jar 只在同一次 workflow 的
job 之間傳遞，**不需要把 repo 設成 public，也不需要在 VM 上開對外下載通道**。

> 🔒 **原則一：build 與 deploy 分開。**
> build job 執行 repo 裡的建置指令（可能被任何有 push 權限的人改動），因此**不注入 SSH 私鑰**；
> 只有 deploy job 才拿到 `VM_SSH_PRIVATE_KEY`。

> 🔒 **原則二：釘選主機指紋。**
> 用 `vars.VM_SSH_HOST_KEY` 事先釘選 VM 的 SSH 主機公鑰、強制 `StrictHostKeyChecking=yes`，
> **不要**用 `ssh-keyscan` 盲目信任首次連線。私鑰寫入 runner 前先 `umask 077`，
> 並在 `if: always()` 的步驟中清除。

因此 Lab 04／05 需要的是 **VM 的 SSH 連線設定**（見下方前置需求），repo 可以維持 private。
Lab 03 教的 workflow artifact 在這裡正是 job 之間交接 jar 的通道。

### 🔴 關於 VM 的 IP

**VM 的 public IP 在編寫本手冊時尚未確定。**
手冊中一律寫成 `<VM_PUBLIC_IP>`（文字）或 `${{ vars.VM_PUBLIC_IP }}`（YAML）。

**講師會在課堂上公布實際 IP**，拿到之後請把它設成你 repo 的 repository variable：

```bash
gh variable set VM_PUBLIC_IP --repo <your-account>/20260903-GH200 --body "<實際IP>"
```

**不要把 IP 寫死在 YAML 裡**——這既是好習慣，也是為了 IP 變動時不用改一堆檔案。

## 前置需求檢查表

開課前請逐項確認：

- [ ] **GitHub 帳號**，並已加入課程使用的組織／可存取課程 repo
- [ ] **Git** 已安裝（`git --version`）
- [ ] **GitHub CLI (`gh`)** 已安裝並登入（`gh auth status` 顯示已登入）
- [ ] **JDK 21** 已安裝（`java -version` 顯示 21 或以上）
- [ ] **不需要**安裝 Maven（用 repo 內的 Maven Wrapper）
- [ ] 編輯器（建議 VS Code，安裝 YAML 擴充套件並開啟「顯示空白字元」）
- [ ] 瀏覽器可以連到 GitHub
- [ ] 你自己帳號底下有一份課程 repo（fork 或 clone），且 **Actions 已啟用**
- [ ] repo 中已建立 environment：`test` 與 `production`
- [ ] 若講師已授權你執行 CD：repo secret `VM_SSH_PRIVATE_KEY`（部署用 SSH 私鑰）
- [ ] repo variables：`VM_PUBLIC_IP` / `VM_SSH_USER` / `VM_SSH_HOST_KEY`（講師提供）
- [ ] repo 可維持 **private**（SSH 部署不需要匿名下載，也不需要 public repo）

### 一鍵設定腳本

上面 repo/environment 設定可以用腳本完成：

**Windows（PowerShell）**
```powershell
cd scripts
.\setup-student-repo.ps1
```

**Linux / macOS（bash）**
```bash
cd scripts
chmod +x setup-student-repo.sh
./setup-student-repo.sh
```

腳本會檢查工具、fork + clone repo、啟用 Actions、建立兩個 environment，並印出下一步。
只有在講師確認**你的 repository 已配置好對應的 VM SSH 存取**之後，才帶參數補上非機密的 variables：

```powershell
.\setup-student-repo.ps1 -VmPublicIp <VM_PUBLIC_IP> -VmSshUser <USER> -VmSshHostKey "<known_hosts 條目>"
```

```bash
VM_PUBLIC_IP=<VM_PUBLIC_IP> VM_SSH_USER=<USER> VM_SSH_HOST_KEY="<known_hosts 條目>" \
  ./setup-student-repo.sh
```

> 私鑰是機密，**不要**放進指令列參數（會留在 shell 歷史）。請由講師另外設定：
>
> **PowerShell：** `Get-Content -Raw -LiteralPath id_deploy | gh secret set VM_SSH_PRIVATE_KEY --repo <your-account>/20260903-GH200`
>
> **bash：** `gh secret set VM_SSH_PRIVATE_KEY --repo <your-account>/20260903-GH200 < id_deploy`

腳本**不會**刪除任何東西、不會覆寫既有目錄、也不會硬編任何 token（一律使用你 `gh` 的既有登入狀態）。

### Lab 04／05 的身分邊界（重要）

VM 的 SSH 私鑰是長期憑證。講師為 class repo 配置的部署金鑰，**不會自動適用到你的 fork**；
每個要實跑部署的 fork 都需要講師另外授權（把對應公鑰加入 VM，並設定該 fork 的
`VM_SSH_PRIVATE_KEY` secret 與 `VM_SSH_HOST_KEY` / `VM_SSH_USER` 變數）。

本課預設：

- Lab 01–03、06：在自己的 fork 實作並執行。
- Lab 04–05：學生先在 fork 寫完 YAML、由講師 review；實際 deployment 由講師在
  class repo 示範，或讓已取得 class repo write access 的學員在指定 branch 操作。
- 若客戶要求每位學員都部署：講師必須為**每一個 fork**設定各自的部署金鑰與最小範圍存取；
  setup script 不會也不應自動取得或散布私鑰。

**不要把講師的 SSH private key、PAT 或任何長期憑證直接發給學員共用。**

### `production` 的核准關卡要自己設

API 可以建立 environment，但 **required reviewer 請你自己在 repo 設定頁的 Environments 中加上**（把你自己加進去即可）。
Lab 05 需要它才能體驗核准流程。

## 檔案結構

```
labs/
├── README.md                     ← 你正在看的這份
├── lab01-first-workflow.md
├── lab02-build-and-test.md
├── lab03-package-artifact.md
├── lab04-deploy-test.md
├── lab05-prod-approval.md
├── lab06-troubleshooting.md
├── lab07-selfhosted-runner.md
├── starters/                     ← 有 TODO 的骨架，從這裡開始
│   ├── lab01.yml ... lab07.yml
│   └── lab06-broken-1.yml ... lab06-broken-4.yml   ← Lab 06 的四道題目
├── solutions/                    ← 完整解答，卡住再看
│   ├── lab01.yml ... lab07.yml
│   └── lab06-fixed-1.yml ... lab06-fixed-4.yml
└── scripts/
    ├── setup-student-repo.ps1
    └── setup-student-repo.sh
```

## 使用的 action 版本

本手冊統一使用下列版本，請照抄，不要自行改版號（不同大版號的參數可能不相容）：

| Action | 版本 | 用途 |
|---|---|---|
| `actions/checkout` | **v5** | 把原始碼抓到 runner |
| `actions/setup-java` | **v6** | 安裝 JDK（temurin / 21） |
| `actions/upload-artifact` | **v7** | 上傳建置產出 |
| `actions/download-artifact` | **v7** | 下載建置產出（job 之間交接 jar） |
| `azure/login` | **v3** | 以 OIDC 登入 Azure（Lab 06 broken-3 與示範 workflow 07 使用） |

> ℹ️ **為什麼 artifact 系列是 v7？**
> artifact 系列的 **v4** 執行時會在 log 中出現 **Node.js 20 deprecation 警告**
> （該 major 版本綁定的 runner runtime 已進入淘汰期）。
> v7 改用較新的 Node runtime，警告即消失。功能與參數（`name` / `path` /
> `if-no-files-found` / `retention-days`）與舊版相同，Lab 03 的教學內容不受影響。
> 其餘 action 維持 `checkout@v5`、`setup-java@v6`、`azure/login@v3`，與課堂 workflow 一致。
>
> 在 log 中看到 deprecation 警告時，正確的處理方式就是**升級 action 的 major 版本**，
> 而不是忽略它——這也是 Lab 06「讀 log」的延伸練習。

## 如果你落後了

課堂節奏很快，落後是正常的。**不要停在原地硬追**，用下面的方式跟上：

1. **先確認你落後在哪一個 lab。** 每個 lab 的「驗收標準」就是檢查點——從最後一個你能全部打勾的 lab 開始算。

2. **直接使用 `solutions/` 追上進度。** 把對應的解答檔複製到 `.github/workflows/`，改成該 lab 要求的檔名，push 讓它跑綠，你就回到主線了。
   例如你卡在 Lab 03，想跟上 Lab 04：
   ```bash
   cp labs/solutions/lab03.yml .github/workflows/lab03-artifact.yml
   git add . && git commit -m "catch up lab03" && git push
   ```

3. **但請務必回頭補上。** 用解答追進度只是為了不錯過現場示範，**課後一定要自己重打一次**——客戶的目標是「你能自己寫 YAML」，不是「你有一份能跑的 YAML」。
   建議做法：把解答檔關掉，照著該 lab 的「你要自己完成的 YAML」骨架**憑記憶重打**，寫不出來的地方才回去看。

4. **各 lab 的相依關係：**
   ```
   Lab01 → Lab02 → Lab03 → Lab04 → Lab05
                              ↘
                               Lab06（建議做完 Lab04 再做）
   Lab07（選修，只需要 Lab01 的基礎）
   ```
   - Lab 06 的 Case 1、2、4 **不需要** Azure 設定，隨時可以做
   - Lab 06 的 Case 3 是獨立的 OIDC 權限題，需要 Azure secrets；若還沒設定好，可以只讀 log 訊息並推理原因

5. **真的追不上就跳到 Lab 06。** 如果時間只夠再做一個 lab，做 Lab 06。
   會寫 YAML 但不會除錯，回公司第一次卡住就前功盡棄；
   會除錯的人，就算 YAML 忘了怎麼寫，也有辦法自己查到答案並修好。

## 給講師的小提醒

- 課前請把實際的 `VM_PUBLIC_IP` / `VM_SSH_USER` / `VM_SSH_HOST_KEY` 準備好發給學員，
  並為要實跑部署的 fork 設定 `VM_SSH_PRIVATE_KEY` secret
- VM 的網路安全群組需要開放 **8080** 與 **8081**；SSH 部署期間才臨時允許來源受控的
  **TCP/22**（例如 `AllowSshFromAzureCloud`），用完即關，**切勿對整個 Internet 常開 22**
- 兩個 systemd unit 只需直接設定各自的 `SERVER_PORT` / `APP_ENVIRONMENT`；
  build metadata 已烤進 jar，**不需要** `EnvironmentFile` 或 `app.env`
- 示範 workflow 07（App Service + OIDC）另需 Azure 端的 federated credential 與
  `AZURE_WEB_APP_NAME` / `AZURE_WEB_APP_HOSTNAME` / `AZURE_RESOURCE_GROUP` 變數，以及
  GitHub secret `AZURE_WEBAPP_CLIENT_ID`（這個 Web App 專屬 identity 的 client ID，
  不是 Lab 06 broken-3 用的舊 `AZURE_CLIENT_ID`；舊 secret 維持不動，等其他 VM／Blob
  相關使用者全部除役後才處理）
- Lab 07 若要實作，需提供 VM 的 SSH 連線方式
