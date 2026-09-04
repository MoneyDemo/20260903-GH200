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
| [Lab 04](lab04-deploy-test.md) | 部署到 **test**（**設計 + 觀察**）：environment、`upload`/`download-artifact`、SSH 部署到 on-prem VM、釘選主機指紋 | **M1** + **M5** | 45 分 |
| [Lab 05](lab05-prod-approval.md) | Promote 到 **production**（**設計 + 觀察**）：核准關卡、保護規則、build once/deploy many | **M5** Secure and Optimize Automation | 30 分 |
| [Lab 06](lab06-troubleshooting.md) | **讀 log 除錯**：四個壞掉的 workflow、debug logging、re-run failed jobs | **M2** Consume and Troubleshoot Workflows | 45 分 |
| [Lab 07](lab07-selfhosted-runner.md) | （選修 / **觀察與設計**）self-hosted runner、label、runner group、組織政策 | **M4** Manage GitHub Actions in the Enterprise | 30 分 |

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

因此 Lab 04／05 是**設計 + 觀察**：學員撰寫並 review 部署 YAML，實際的 SSH 部署由講師在 class
repo 示範（VM 的 SSH 連線設定只在 class repo 端配置，學員不會拿到）。repo 可以維持 private。
Lab 03 教的 workflow artifact 在這裡正是 job 之間交接 jar 的通道。

### 🔴 關於 VM 的 IP

**VM 的 public IP 在編寫本手冊時尚未確定。**
手冊中一律寫成 `<VM_PUBLIC_IP>`（文字）或 `${{ vars.VM_PUBLIC_IP }}`（YAML）。

這是 YAML 設計上的**名稱佔位**。實際 IP 只由講師在 class repo 端配置；**學員不需要、也不會拿到
真實 IP，更不需要把它設成自己 repo 的 variable**。**不要把 IP 寫死在 YAML 裡**——一律用
`${{ vars.VM_PUBLIC_IP }}` 這個名稱引用。

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
- [ ] repo 中已建立 environment：`test` 與 `production`（供 Lab 04/05 的 YAML 對照用）
- [ ] repo 可維持 **private**（CI/YAML 練習不需要 public repo）
- [ ] **你不需要**任何課程 VM 的 SSH 私鑰、host key、public IP 或 SSH 使用者，也不需要任何
      Azure 身分——Lab 04／05 的實際部署由講師在 class repo 實跑、學員觀察

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

腳本會檢查工具、fork + clone repo、啟用 Actions、建立兩個 environment（供 Lab 04/05 的 YAML
對照用），並印出下一步。**腳本不接收、也不散布任何部署憑證**：它不會設定 VM 的 SSH 私鑰、
host key、public IP、SSH 使用者，也不會設定任何 Azure 身分。

> 🔒 **學員不會拿到課程 VM 的憑證。** Lab 04／05 的實際 SSH 部署與 production 核准只由講師在
> class repo（`MoneyDemo/20260903-GH200`）示範；VM 的 SSH 私鑰（可 sudo 的長期憑證）、host key、
> IP、SSH 使用者與任何 Azure 身分都只在 class repo 端配置。**絕不**把講師的 SSH private key、
> PAT 或任何長期憑證發給學員共用，也**不需要**在學員 fork 上設定這些。

腳本**不會**刪除任何東西、不會覆寫既有目錄、也不會硬編任何 token（一律使用你 `gh` 的既有登入狀態）。

### Lab 04／05 的身分邊界（重要）

VM 的 SSH 私鑰是可 sudo 的長期憑證，只由講師保管。**學員在自己的 fork 不會、也不需要拿到
課程 VM 的 SSH 私鑰、host key、public IP、SSH 使用者或任何 Azure 身分。**

本課預設：

- Lab 01–03、06：在自己的 fork 實作並執行（純 CI / YAML / 除錯）。
- Lab 04–05：**設計 + 觀察**——學生在自己的 fork 撰寫並 review 部署 YAML；實際的 test/prod
  部署由講師在 class repo 示範，學員觀察其已驗證的 run 與 log。YAML 中的
  `${{ vars.VM_* }}`、`${{ secrets.VM_SSH_PRIVATE_KEY }}` 都是設計上的名稱佔位，由講師在
  class repo 端配置。
- 學員 fork **不需要**為了「實跑部署」而取得任何部署金鑰；setup script 也不會取得或散布私鑰。

**不要把講師的 SSH private key、PAT 或任何長期憑證直接發給學員共用。**

### `production` 的核准關卡（觀察）

`production` 的 required reviewer 由講師在 class repo 端設定；**學員不擔任 reviewer、不自行核准**。
Lab 05 讓你觀察講師示範 run 停在 `production` 等待核准、由授權者核准後才部署的完整流程。

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

本手冊在**部署與 self-hosted 相關的特權 workflow（Lab 04／05／07，以及課堂 workflow
04-08）**一律把 `uses:` **釘選成不可變的 40-hex commit SHA**，並在旁邊用註解保留原始版本
tag 方便閱讀。**不要**把可變 tag（`@v5` 這種）複製進這些特權 workflow——可變 tag 可被
重新指向，等於把供應鏈信任交給 tag 持有者。入門與除錯用的 Lab 01-03、Lab 06 為了教學
可讀性可沿用可讀 tag，但 Lab 06 broken-3 的 `azure/login`（OIDC 登入用的特權 action，
即使這題只用全為零的佔位 UUID、不會真的登入）同樣釘選成 SHA。

| Action | 原 tag | 釘選 SHA（特權 workflow 用） | 用途 |
|---|---|---|---|
| `actions/checkout` | `@v5` | `fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09` | 把原始碼抓到 runner |
| `actions/setup-java` | `@v6` | `dd06d9cba3e5552c54d9f8ea23572deb30010f7c` | 安裝 JDK（temurin / 21） |
| `actions/upload-artifact` | `@v7` | `043fb46d1a93c77aae656e7c1c64a875d1fc6a0a` | 上傳建置產出 |
| `actions/download-artifact` | `@v7` | `37930b1c2abaa49bbe596cd826c3c89aef350131` | 下載建置產出（job 之間交接 jar） |
| `azure/login` | `@v3` | `7ddb5af1ef8758cf1353cf3b42f940aee27ba21c` | 以 OIDC 登入 Azure（Lab 06 broken-3／fixed-3 用**全為零的佔位 UUID**、不會真的登入；**講師示範** workflow 07 才用真實身分） |

> ℹ️ **釘選寫法**：
> `uses: actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09  # 原 tag @v5`。
> 課堂 workflow 04-08 與 Lab 04／05／07 的解答都採這種寫法。

> ℹ️ **為什麼 artifact 系列是 v7？**
> artifact 系列的 **v4** 執行時會在 log 中出現 **Node.js 20 deprecation 警告**
> （該 major 版本綁定的 runner runtime 已進入淘汰期）。
> v7 改用較新的 Node runtime，警告即消失。功能與參數（`name` / `path` /
> `if-no-files-found` / `retention-days`）與舊版相同，Lab 03 的教學內容不受影響。
> 其餘 action 對應 `checkout@v5`、`setup-java@v6`、`azure/login@v3` 的同一個大版號，與課堂
> workflow 一致；差別只在特權 workflow 一律釘選成上表的 40-hex SHA。
>
> 在 log 中看到 deprecation 警告時，正確的處理方式就是**升級 action 的 major 版本**，
> 而不是忽略它——這也是 Lab 06「讀 log」的延伸練習。

## 如果你落後了

課堂節奏很快，落後是正常的。**不要停在原地硬追**，用下面的方式跟上：

1. **先確認你落後在哪一個 lab。** 每個 lab 的「驗收標準」就是檢查點——從最後一個你能全部打勾的 lab 開始算。

2. **直接使用 `solutions/` 追上進度（只限 CI 練習 lab 01–03、06）。** 把對應的解答檔複製到 `.github/workflows/`，改成該 lab 要求的檔名，push 讓它跑綠，你就回到主線了。
   > ⚠️ **只對 Lab 01–03、06 這麼做。** Lab 04／05 是設計 + 觀察、Lab 07 的解答是惰性參考範本（`if: ${{ false }}`，永不執行）——**不要**把 `solutions/lab04.yml`、`lab05.yml`、`lab07.yml` 複製進 `.github/workflows/` 去「跑綠」，它們的實跑由講師在 class repo 示範。
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
   - Lab 06 的 Case 3 是獨立的 OIDC 權限題，**不需要任何 secret、也不需要真的登入 Azure**：它用
     全為零的佔位 UUID，讓你診斷缺少 `id-token: write` 的失敗（修好後會前移到 Azure 認證階段失敗，
     這是預期結果）

5. **真的追不上就跳到 Lab 06。** 如果時間只夠再做一個 lab，做 Lab 06。
   會寫 YAML 但不會除錯，回公司第一次卡住就前功盡棄；
   會除錯的人，就算 YAML 忘了怎麼寫，也有辦法自己查到答案並修好。

## 給講師的小提醒

- **VM 的部署憑證只在 class repo 端配置，不發給學員 fork。** 課前在 class repo
  （`MoneyDemo/20260903-GH200`）備妥 `VM_PUBLIC_IP` / `VM_SSH_USER` / `VM_SSH_HOST_KEY` 變數，
  以及 `test` 與 `production` 兩個 Environment 各一份的 `VM_SSH_PRIVATE_KEY` Environment secret
  （**不是** repository secret），供講師實跑 `04`/`05`/`06` 示範用。**不要**把 SSH 私鑰、host key、
  IP 或 SSH 使用者發給學員的 fork——Lab 04／05 是學員設計 + 觀察，講師實跑。
- VM 的網路安全群組需要開放 **8080** 與 **8081**；TCP/22 由 Terraform 以持久的
  `AllowSshFromAzureCloud` 規則**只對來源 `AzureCloud`** 開放，供 GitHub-hosted runner 在
  課程期間連線，**不要在每次部署後把它關掉，也切勿改成對整個 Internet 開放 22**
- 兩個 systemd unit 只需直接設定各自的 `SERVER_PORT` / `APP_ENVIRONMENT`；
  build metadata 已烤進 jar，**不需要** `EnvironmentFile` 或 `app.env`
- 示範 workflow 07（App Service + OIDC）另需 Azure 端的 federated credential 與
  `AZURE_WEB_APP_NAME` / `AZURE_WEB_APP_HOSTNAME` / `AZURE_RESOURCE_GROUP` 變數，以及
  GitHub secret `AZURE_WEBAPP_CLIENT_ID`（這個 Web App 專屬 identity 的 client ID）。舊共用
  identity 的 VM／Blob 部署角色（四個 federated credential、VM Contributor、Blob Contributor、
  Blob Reader）已完成除役移除。**這個 `AZURE_WEBAPP_CLIENT_ID` 只在 `07` 講師示範用**；
  **Lab 06 broken-3／fixed-3（M2 OIDC troubleshooting）不再引用它、也不引用任何 secret**——改用
  全為零的佔位 UUID，讓學員診斷缺少 `id-token: write` 的失敗（修好後前移到 Azure 認證階段失敗，
  屬預期結果，學員不需真的登入 Azure）
- **⚠️ 本 repo（`MoneyDemo/20260903-GH200`）與 `MoneyYu/GH-200` 目前共用同一個 Linux Web
  App，兩邊的 workflow 07 不可同時 dispatch**——曾實測同時觸發時兩邊的 OneDeploy 都因
  App Service 啟動逾時失敗。**規則：在另一個 repo 的 `07` run 顯示成功、且該 Web App
  的 `/api/info` 回應已核對等於該次 commit 的 `buildSha` 之前，不得啟動本 repo 的
  `07`**；反之亦然。誰的 `07` 最後成功部署，App Service 上的版本就會被覆蓋為誰的
  （last successful deployment wins，沒有版本回滾）。若仍發生碰撞或其中一邊逾時失敗，
  等兩邊的 run 都跑完（不論成功或失敗）後，只重新 dispatch 真正想要上線的那個 repo 的
  `07`，並重新以 `/api/info` 核對 `buildSha` 相符才視為完成。這是講師／agent 需人工遵守
  的操作排程規則，**不是**、也不要用 `concurrency:` group 實作 GitHub 跨 repo 的假鎖
  （fake cross-repo lock）；安排班級與另一 repo 的示範時程時要避開重疊
- **⚠️ 共用 VM 部署排序（04/05/06/08）：** 本 repo 與 `MoneyYu/GH-200` 的 `04`/`05`/`06`（SSH
  部署）與 `08`（same-VM runner）都寫入同一台 Linux VM 的 `simpleweb-test`（8080）／
  `simpleweb-prod`（8081）。比照上面 workflow 07 的排序：在另一個 repo 前一個 VM 部署 run 顯示
  成功、且相關 `8080`／`8081` 的 `/api/info` 已確認回報該次 commit 的 `buildSha` 之前，不得啟動
  本 repo 的 `04`/`05`/`06`/`08`。這是人工排程規則，不是 GitHub 跨 repo 鎖，不要用
  `concurrency:` group 偽造
- **Self-hosted runner（08）邊界：** 本 public repo（`MoneyDemo/20260903-GH200`）刻意**不
  註冊任何 self-hosted runner**，且 `08.selfhosted-runner` 是**惰性參考範本**：其 job 以字面
  `if: ${{ false }}` **永遠跳過**，在本 public upstream 與**任何**學員 fork／私有複本上都不會執行，
  也不得為此在其上註冊 runner 或指向課程 VM。實際的 M4 demo 由講師在私有的 `MoneyYu/GH-200`
  上，用其**常駐**的 self-hosted runner 進行（那台 runner 是常駐課程基礎設施，示範後**不移除**、
  runner 數不歸零）。學員的 Lab 07 為觀察／設計練習，不註冊 runner、不連課程 VM
- **本 repo 主 default branch 的現行主線已 live dispatch 成功一次**（`demo-java-04` run
  [33818661549](https://github.com/MoneyDemo/20260903-GH200/actions/runs/33818661549)、
  `demo-java-05` reviewer-approved run
  [33818771304](https://github.com/MoneyDemo/20260903-GH200/actions/runs/33818771304)、
  `demo-java-06`（於上方 Azure identity 除役**之後**再次 dispatch）run
  [33820467021](https://github.com/MoneyDemo/20260903-GH200/actions/runs/33820467021)、
  `demo-java-07-deploy-webapp`（Azure CLI JAR deploy，同樣在除役之後）run
  [33820921333](https://github.com/MoneyDemo/20260903-GH200/actions/runs/33820921333)、
  `demo-java-08-selfhosted-runner` run
  [33819196217](https://github.com/MoneyDemo/20260903-GH200/actions/runs/33819196217)（**此為安全修復前的歷史紀錄，使用的是事後已移除的 public class runner；現在本 public repo 的 `08` 已改成惰性參考範本 `if: ${{ false }}`，永遠略過，切勿為重現此 run 而在本 public repo 或任何學員 fork 重新註冊 runner**）；
  `07` 的 `/api/info` build SHA 與該次 default-branch run SHA 完全相符。
  `demo-java-09-troubleshooting` 維持預期失敗（教學用途，不要修成會成功）：run
  [33819300197](https://github.com/MoneyDemo/20260903-GH200/actions/runs/33819300197)。
  本 repo 的 `test`／`production` Environments 現在都只允許 default branch 部署，
  `production` 的 required-reviewer approval gate 維持不變。舊的 repository 層級
  `VM_SSH_PRIVATE_KEY` 副本已刪除，本 repo 現在只剩 `test`／`production` 兩個
  Environment 各一份的 Environment secret。
- **課堂 `08` 是唯讀參考範本，學員不在任何 repo 實跑 self-hosted runner。** 課堂唯一的 live
  same-VM runner demo 由講師在私有的 `MoneyYu/GH-200` 上以其常駐 runner 示範。若有學員想在
  **課後**親手體驗，只能在**經講師同意的另一台隔離機器 + 另一個獨立 private repository**上進行，
  **絕不**使用課程 VM、課程 repo 或其 fork（見 [`lab07-selfhosted-runner.md`](lab07-selfhosted-runner.md) 的 F 段）
