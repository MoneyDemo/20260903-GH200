# Lab 07（選修 / 進階）— Self-hosted runner

> 這個 lab 是**選修**。如果課堂時間不夠，或你的 repo 沒有足夠權限，
> 可以只讀懂概念與安全警告，不實際操作。
> 實際操作需要登入講師的 VM，請依講師指示進行。

## 學習目標

做完這個 lab，你應該可以：

- 說明 GitHub 託管 runner 與 self-hosted runner 的差異與各自適用情境
- 在一台 Linux VM 上註冊一個 self-hosted runner，並加上自訂 label
- 用 `runs-on:` 搭配多個 label 精準指定要跑在哪一台機器
- 實作 workflow 08 教的**同一台 VM 本機部署**模式：build 時把完整 commit SHA 烤進 artifact、
  在本機安裝並重啟 `simpleweb-test`、用本機 `curl` 驗到 exact-SHA 的語義化 smoke test
- 說明 runner group 與組織政策在企業中扮演的角色
- **完整說出 self-hosted runner 的五個安全邊界**：同一台 VM 上 runner 與部署目標共用只是課堂簡化、
  這條路徑全程零 inbound SSH、正式環境應把 runner 與部署目標分開、絕不能服務不受信任的
  fork PR、以及 GitHub 不會把 job 派給 30 天內沒有更新 runner application 的 runner
- 用完之後把 runner 乾淨地移除

## 對應模組

**Module 4 — Manage GitHub Actions in the Enterprise**（self-hosted runners、runner groups、組織政策、secrets 治理）

## 前置需求

- 已完成 [Lab 01](lab01-first-workflow.md)
- 講師提供 VM 的 SSH 連線方式（帳號與 `<VM_PUBLIC_IP>`，課堂上公布）
- 你對該 repo 有管理權限（註冊 runner 需要）
- ⚠️ 這台 VM 也跑著 `simpleweb-prod`（正式環境）——**這個 lab 只會重啟 `simpleweb-test`**
  （這是本 lab 的教學重點，同 workflow 08），**絕對不要**去停用或更動 `simpleweb-prod`

## 步驟

### A. 先弄懂差別

| | GitHub 託管 runner | Self-hosted runner |
|---|---|---|
| 機器由誰維護 | GitHub | **你自己** |
| 每次執行的環境 | 全新、乾淨、用完即毀 | **狀態會保留**，上一個 job 留下的東西還在 |
| 預裝工具 | 非常多（JDK、Docker、CLI…） | 你裝什麼才有什麼 |
| 網路位置 | GitHub 的雲端 | 你的網段內——**可以直連內網資源** |
| 硬體 | 固定規格 | 你想給多少給多少（GPU、特殊硬體、特定 OS） |
| 主要動機 | 開箱即用 | 存取內網、合規要求、特殊環境、大型硬體需求 |

企業選擇 self-hosted 最常見的理由是：**要連進防火牆內的資料庫、成品庫或部署目標**。

### B. 註冊 runner

1. 到你 repo 的設定頁，找到 Actions 底下的 Runners，選擇新增 self-hosted runner，平台選 **Linux x64**。

2. GitHub 會產生一段**專屬於你、有時效性的**指令。內容大致是：下載 runner 套件 → 驗證雜湊 → 解壓縮 → 執行設定。**請直接照抄畫面上的指令**，不要用講義裡的版本號（runner 版本會一直更新）。

3. SSH 進 VM，在你自己的家目錄底下建立獨立目錄再操作，例如：
   ```bash
   mkdir -p ~/actions-runner-<你的名字> && cd ~/actions-runner-<你的名字>
   ```
   > 每位學員各自一個目錄，不要互相覆蓋。

4. 執行設定時**加上自訂 label**：
   ```bash
   ./config.sh --url https://github.com/<your-account>/<your-repo> \
               --token <畫面上給你的 token> \
               --labels gh200 \
               --unattended
   ```
   - `--labels` 加上去的是**額外**標籤；`self-hosted`、`Linux`、`X64` 這幾個是自動加上的
   - runner 名稱建議也帶上你的名字，避免和同學混淆

5. 啟動 runner：
   ```bash
   ./run.sh
   ```
   這是前景執行，關掉 SSH 就會停。課堂練習用前景就好，方便你直接看到它接到 job。
   （正式環境會用 `sudo ./svc.sh install` + `sudo ./svc.sh start` 註冊成系統服務。）

6. 回到 GitHub 的 Runners 清單，確認你的 runner 狀態是 **Idle**，且 label 中有 `gh200`。

### C. 用 workflow 指到它

7. 建立 `.github/workflows/lab07-selfhosted.yml`，從 [`starters/lab07.yml`](starters/lab07.yml) 開始。

8. **補上 `runs-on:`。** 需要同時符合多個 label 時，要寫成清單：
   ```yaml
   runs-on: [ self-hosted, Linux, X64, gh200 ]
   ```
   意思是「找一台**同時**具備這四個 label 的 runner」。只寫 `self-hosted` 也能跑，但在有多台機器的環境中就無法精準指定——這正是 label 的用途。

9. **補上驗證用的 steps：** 印出 `hostname`、`uname -a`、`whoami`、`RUNNER_NAME`、`RUNNER_OS`，讓你確認它真的跑在 VM 上。

10. **感受一下差別：** 再加一個 step 執行 `java -version`。GitHub 託管的 Ubuntu runner 預裝了 JDK，這台 VM 可能沒有。如果失敗了——那正是這個 lab 想讓你體會的重點：**self-hosted 的環境要自己準備。**

11. **補上 workflow 08 教的「同一台 VM 本機部署」流程：**
    - `actions/checkout@v5`（`persist-credentials: false`）+ `actions/setup-java@v6`（temurin、21）
    - **Build 時把完整 commit SHA 與 UTC 時間烤進 artifact：**
      ```bash
      ./mvnw -B verify -Dapp.build.sha="$GITHUB_SHA" -Dapp.build.time="$(date -u +%FT%TZ)"
      ```
    - **本機部署（不需要遠端登入，因為 runner 就在目標 VM 上）：**
      ```bash
      sudo install -o simpleweb -g simpleweb -m 0644 target/simpleweb.jar /opt/simpleweb/test/simpleweb.jar
      sudo systemctl restart simpleweb-test
      sudo systemctl is-active simpleweb-test
      ```
      這條路徑**完全不需要 SSH key**，因為建置與部署發生在同一台機器上——這正是
      self-hosted runner 在「內網部署」情境下的價值：省掉一段網路連線。
    - **語義化 exact-SHA smoke test：** 打 `http://localhost:8080/api/info`，解析 JSON 取出
      `buildSha`，比對是否等於 `$GITHUB_SHA`（不是只看 HTTP 200，而是驗證「真的是這次
      commit 的版本」——回應更新的舊版本 jar 內容一樣會回 200，但 SHA 會對不上）。
    - 把 runner 名稱、hostname、service 名稱與這次的 build SHA 寫進 `$GITHUB_STEP_SUMMARY`。

12. **手動觸發**（這個 workflow 只有 `workflow_dispatch`），同時觀察 SSH 視窗中 `./run.sh` 的輸出，你會看到它即時接到 job、build、本機部署，並回報 smoke test 結果。

### D. ⚠️ 安全：這一段一定要讀

**Self-hosted runner 最大的風險：任何能讓 workflow 在上面執行的人，等同於可以在你的機器上執行任意程式碼。**

這個 lab 的本機部署路徑必須守住以下**五個安全邊界**：

1. **同一台 VM 只是課堂簡化。** runner 和部署目標（`simpleweb-test`）共用同一台機器，是為了讓課堂
   demo 簡單；正式環境**應該把 runner 和實際要部署的正式主機分開**，避免 runner 一旦被入侵就等於
   直接拿到正式主機權限。
2. **這條路徑全程零 inbound SSH。** 建置與部署都發生在 runner 本機，不需要對外開放任何 SSH 埠、
   也不需要 `VM_SSH_PRIVATE_KEY` 這類長期憑證——這是它和 workflow 04-06 的 SSH 路徑最大的差異。
3. **絕對不要**讓 self-hosted runner 執行來自 fork 的 pull request（見下方第 1 點細節）。
4. **狀態會殘留**，需要額外的清理紀律（見下方第 2 點細節）。
5. **GitHub 不會把 job 派給 30 天內沒有更新 runner application 的 runner**——這是治理上的
   一個保護機制，逼你保持 runner 版本更新。

具體來說：


1. **絕對不要**讓 public repo 的 self-hosted runner 執行來自 fork 的 pull request。
   任何陌生人 fork 你的 repo、在 workflow 裡塞一行惡意指令、發一個 PR，那行指令就會在你的內網機器上執行。GitHub 的官方文件對這一點有非常明確的警告。
   本 lab 的 workflow **只用 `workflow_dispatch`**，就是為了避免這個風險。

2. **狀態會殘留。** 上一個 job 留下的檔案、環境變數、cache、甚至被竄改的工具，都會影響下一個 job。託管 runner 每次都是新的，self-hosted 不是。正式環境常見的緩解手段是讓每個 job 跑在拋棄式的容器或 VM 中（ephemeral runner）。

3. **它在你的內網裡。** 這既是它的價值，也是它的風險——一旦被利用，攻擊者就取得了一個內網立足點。

4. **權限最小化。** runner 的服務帳號不要用 root，只給它完成工作所需的權限。

### E. 概念：runner group 與組織政策

規模一大，就不能讓每個人各自亂裝 runner。企業的治理手段包括：

- **Runner groups** — 把 runner 分組，並限制「哪些 repository 或哪些 workflow 可以使用這一組」。例如把能碰 production 的 runner 單獨一組，只開放給特定 repo。
- **組織／企業層級的 Actions 政策** — 限制可以使用哪些 action（例如只允許 GitHub 官方與已驗證的建立者、或明確列白名單）、是否允許 fork PR 執行、預設的 `GITHUB_TOKEN` 權限等。
- **Secrets 治理** — 在組織層級集中管理 secrets 並限定可存取的 repo，搭配 environment secrets 做環境隔離。原則不變：**能用 OIDC 就不要存長期憑證**（本課對應的是 workflow 07 的 App Service + OIDC 部署路徑；相對地，04-06 的 SSH 路徑就必須自己保管長期私鑰）。

### F. 收尾：把 runner 移除

**做完一定要移除**，否則這台機器會一直掛在別人的 repo 上。

13. 在 SSH 視窗按 `Ctrl+C` 停掉 `./run.sh`。

14. 回到 GitHub Runners 頁面取得移除用的 token，然後在 VM 上執行：
    ```bash
    ./config.sh remove --token <畫面上給你的移除 token>
    ```
    （若你先前有安裝成服務，要先 `sudo ./svc.sh stop` 再 `sudo ./svc.sh uninstall`。）

15. 確認 GitHub 的 Runners 清單中已經看不到你的 runner。

16. 刪除你自己建立的那個目錄：
    ```bash
    cd ~ && rm -rf ~/actions-runner-<你的名字>
    ```
    > ⚠️ 只刪你自己建立的那一個目錄。**不要**對 `/opt/simpleweb` 或其他人的目錄做任何刪除。

## 你要自己完成的 YAML

Starter：[`starters/lab07.yml`](starters/lab07.yml)

```yaml
name: Lab07 Self-hosted Runner

# 只用 workflow_dispatch —— 不要加 pull_request！
on:
  workflow_dispatch:

permissions:
  contents: read

jobs:
  on-self-hosted:
    # TODO 1: runs-on 指向 self-hosted + Linux + X64 + gh200
    runs-on: TODO-RUNNER-LABELS
    steps:
      # TODO 2: 印出 hostname / uname -a / whoami / RUNNER_NAME / RUNNER_OS
      # TODO 3: java -version（失敗也沒關係，重點是體會差異）
      # TODO 4: actions/checkout@v5（persist-credentials: false）+ actions/setup-java@v6（temurin / '21'）
      # TODO 5: Build 時把完整 commit SHA 與 UTC 時間烤進 artifact：
      #   ./mvnw -B verify -Dapp.build.sha="$GITHUB_SHA" -Dapp.build.time="$(date -u +%FT%TZ)"
      # TODO 6: 本機部署（不需要 SSH，runner 就在目標 VM 上）：
      #   sudo install ... target/simpleweb.jar /opt/simpleweb/test/simpleweb.jar
      #   sudo systemctl restart simpleweb-test，再確認 is-active
      # TODO 7: 語義化 smoke test：curl http://localhost:8080/api/info，驗到 buildSha == $GITHUB_SHA
      # TODO 8: 把環境資訊「與」五個安全邊界寫進 $GITHUB_STEP_SUMMARY
```

## 驗收標準

- [ ] GitHub 的 Runners 清單中出現你的 runner，狀態 **Idle**，含 label `gh200`
- [ ] 手動觸發後，job 成功執行且顯示**綠色勾勾**
- [ ] log 中的 `hostname` 是那台 VM，**不是** GitHub 託管 runner 的名稱
- [ ] 你在 SSH 視窗中親眼看到 runner 接到 job
- [ ] `simpleweb-test`（port 8080）在本機被重新安裝並重啟，**不經過 SSH**
- [ ] smoke test 驗到 `/api/info` 的 `buildSha` 等於這次觸發的完整 commit SHA
- [ ] Job Summary 顯示 runner 名稱、hostname，以及五個安全邊界的說明
- [ ] 你能說出「為什麼這個 workflow 不能加上 `pull_request` 觸發」
- [ ] 你能完整說出五個安全邊界：同一台 VM 只是課堂簡化、零 inbound SSH、正式環境要分離
      runner 與正式主機、絕不服務不受信任的 fork PR、30 天未更新的 runner 拿不到 job
- [ ] **收尾完成**：runner 已從 GitHub 移除，VM 上你建立的目錄已刪除
- [ ] `simpleweb-test` 與 `simpleweb-prod` 兩個服務仍然正常（`:8080`、`:8081` 都還通）

## 常見錯誤

| 症狀 | 原因 | 修法 |
|---|---|---|
| job 一直卡在 queued | 沒有 runner 符合 `runs-on` 的**全部** label | 檢查拼字與大小寫（`X64` 不是 `x64`）；確認 runner 是 Idle |
| `runs-on: self-hosted, Linux` 直接語法錯 | 多個 label 要寫成清單 | 用 `[ a, b, c ]` 或多行 `- ` 清單 |
| 註冊時 token 無效 | 註冊 token 有時效 | 回 GitHub 頁面重新產生 |
| `./config.sh` 說已經設定過 | 同一目錄重複設定 | 先 `./config.sh remove`，或換一個乾淨目錄 |
| 關掉 SSH 後 runner 就離線 | `./run.sh` 是前景程序 | 課堂上正常；正式環境請裝成服務 |
| `java: command not found` | self-hosted 沒有預裝工具 | 這是預期的；正式用途要自己裝，或在 workflow 中用 `setup-java` |
| 同學之間互相搶到 job | 大家用同一個 label | label 加上自己的名字做區隔 |
| 做完忘了移除 runner | — | 一定要完成收尾步驟 |
| `sudo install` 說 permission denied | runner 服務帳號沒有對 `/opt/simpleweb/test` 的 sudo 權限 | 這是 VM 上既有的權限設定，確認你用的是講師配置好的帳號，不要自己改權限 |
| smoke test 打 `:8080` 一直連不上 | `simpleweb-test` 沒有成功重啟，或 build 失敗 | 檢查 `Build and test` step 的輸出，以及 `systemctl is-active simpleweb-test` |
| smoke test 顯示的 `buildSha` 是舊版本 | 部署 step 沒有真的覆蓋到 `/opt/simpleweb/test/simpleweb.jar` | 確認目標路徑打對，而且是 `test` 不是 `prod` |

## 解答

[`solutions/lab07.yml`](solutions/lab07.yml)
