# Lab 07（選修 / 觀察與設計）— Self-hosted runner

> 這個 lab 是**選修，而且是「觀察 + 設計」而非動手註冊 runner**。
> 學員**不會**、也**不需要**在任何機器上註冊 self-hosted runner，**不會**以 SSH 連入課程 VM，
> 也**不需要**對課程 VM 有任何存取權。你要做的是：**讀懂** self-hosted runner 的 YAML 與
> 安全邊界，並**觀察講師在私有 `MoneyYu/GH-200` 上實跑的 same-VM runner demo**。

> 🔒 **這條路徑的信任邊界（安全）：**
> - **課堂唯一的 live same-VM runner demo，只由講師在私有的 `MoneyYu/GH-200` 上，用其
>   「常駐」的 self-hosted runner 進行**（那台 runner 是常駐課程基礎設施，示範後不移除、
>   runner 數不歸零）。
> - **本 public class repo `MoneyDemo/20260903-GH200` 的 `08.selfhosted-runner` 是「惰性參考
>   範本」**：它的 job 以字面 `if: ${{ false }}` **永遠跳過**，在本 repo 與**任何**學員 fork 或
>   私有複本上都不會執行。它存在的目的只是讓你**閱讀與設計討論**，不是拿來跑的。
> - **共用的課程 VM 只由講師管理。** 任何學員的 public 或 private fork／複本都**不得**把 runner
>   指向這台共用 VM，也不得為了讓 `08` 或本 lab「跑起來」而在其上註冊 runner。
> - 若你想在課後**自己動手**體驗（見文末「選修：課後的隔離實驗」），必須用**另一台你自己、
>   經講師同意的隔離機器**與**另一個你自己擁有的獨立 private repository**——**絕不**使用課程
>   VM、課程 repo 或其 fork。

## 學習目標

做完這個 lab，你應該可以：

- 說明 GitHub 託管 runner 與 self-hosted runner 的差異與各自適用情境
- **讀懂** `runs-on:` 如何用多個 label 精準指定要跑在哪一台機器
- 讀懂 workflow 08 教的**同一台 VM 本機部署**模式（build 時把完整 commit SHA 烤進 artifact、
  在本機安裝並重啟 `simpleweb-test`、用本機 `curl` 驗到 exact-SHA 的語義化 smoke test），
  並說明為什麼這條路徑**全程零 inbound SSH**
- **完整說出 self-hosted runner 的五個安全邊界**：同一台 VM 上 runner 與部署目標共用只是課堂簡化、
  這條路徑全程零 inbound SSH、正式環境應把 runner 與部署目標分開、絕不能服務不受信任的
  fork PR、以及 GitHub 不會把 job 派給 30 天內沒有更新 runner application 的 runner
- 說明 runner group 與組織政策在企業中扮演的角色
- **觀察**講師在私有 `MoneyYu/GH-200` 上的 `08` 執行與 log，並對照上述邊界

## 對應模組

**Module 4 — Manage GitHub Actions in the Enterprise**（self-hosted runners、runner groups、組織政策、secrets 治理）

## 前置需求

- 已完成 [Lab 01](lab01-first-workflow.md)（理解 workflow / job / step 的基礎）
- 能開啟本 repo 的 [`starters/lab07.yml`](starters/lab07.yml)、[`solutions/lab07.yml`](solutions/lab07.yml)
  與 [`.github/workflows/08.selfhosted-runner.yml`](../.github/workflows/08.selfhosted-runner.yml) 閱讀
- 課堂上能看到講師分享的 `MoneyYu/GH-200` `08` run 頁面與 log
- **不需要**：課程 VM 的存取權、runner 註冊權限、SSH 連線、任何長期憑證

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

### B. 讀懂參考範本的 YAML（設計）

打開三份**唯讀參考**檔案，對照著讀——**不要**把它們複製出去跑，也不要註冊 runner：

- [`starters/lab07.yml`](starters/lab07.yml)：帶 `# TODO:` 的設計骨架
- [`solutions/lab07.yml`](solutions/lab07.yml)：完整的參考寫法
- [`.github/workflows/08.selfhosted-runner.yml`](../.github/workflows/08.selfhosted-runner.yml)：
  課堂用的惰性參考範本（`if: ${{ false }}`，永遠跳過）

閱讀時請在紙上或你自己的設計筆記中回答：

1. **`runs-on:` 為什麼是清單？** 需要同時符合多個 label 時要寫成清單：
   ```yaml
   runs-on: [ self-hosted, Linux, X64, gh200 ]
   ```
   意思是「找一台**同時**具備這四個 label 的 runner」。只寫 `self-hosted` 也能跑，但在有多台
   機器的環境中就無法精準指定——這正是 label 的用途。

2. **為什麼這條部署路徑不需要 SSH？** 因為 runner 就在目標 VM 上，`build` 與 `deploy` 發生在
   同一台機器，部署變成單純的本機檔案複製（`sudo install` + `systemctl restart`），
   不需要對外開放任何 SSH 埠、也不需要 `VM_SSH_PRIVATE_KEY` 這類長期憑證——這是它和
   workflow 04-06 的 SSH 路徑最大的差異。

3. **為什麼只用 `workflow_dispatch`、不加 `pull_request`？** 見下方 D 段的安全邊界。

4. **語義化 smoke test 在驗什麼？** 打 `http://localhost:8080/api/info`，解析 JSON 取出
   `buildSha`，比對是否等於 `$GITHUB_SHA`——不是只看 HTTP 200，而是驗證「真的是這次 commit
   的版本」。

### C. 觀察講師的私有 `MoneyYu/GH-200` `08` 執行（觀察）

課堂唯一的 live same-VM runner demo 在**私有的 `MoneyYu/GH-200`** 上進行。請跟著講師分享的
run 頁面與 log **觀察**，並記下：

1. `runs-on` 的 label 如何讓 job 落到那台常駐 self-hosted runner。
2. log 中的 `hostname` 是那台 VM，**不是** GitHub 託管 runner 的名稱。
3. `simpleweb-test`（port 8080）在**本機**被重新安裝並重啟，**完全不經過 SSH**。
4. smoke test 如何用 `/api/info` 的 `buildSha` 佐證部署到的是這次的 commit。
5. 對照 D 段，逐一指出**五個安全邊界**分別出現在 log 的哪裡（或為什麼看不到——例如「零 inbound
   SSH」正是因為沒有任何 SSH 連線步驟）。

> 本 repo 的 `08` 是惰性參考範本（`if: ${{ false }}`），觀察時會看到它被**略過（skipped）**——
> 這是正確、預期的結果，代表本 public repo 與學員 fork 都不會在課程 VM 上跑 self-hosted runner。

### D. ⚠️ 安全：這一段一定要讀

**Self-hosted runner 最大的風險：任何能讓 workflow 在上面執行的人，等同於可以在你的機器上執行任意程式碼。**

這個模式必須守住以下**五個安全邊界**：

1. **同一台 VM 只是課堂簡化。** runner 和部署目標（`simpleweb-test`）共用同一台機器，是為了讓課堂
   demo 簡單；正式環境**應該把 runner 和實際要部署的正式主機分開**，避免 runner 一旦被入侵就等於
   直接拿到正式主機權限。
2. **這條路徑全程零 inbound SSH。** 建置與部署都發生在 runner 本機，不需要對外開放任何 SSH 埠、
   也不需要 `VM_SSH_PRIVATE_KEY` 這類長期憑證——這是它和 workflow 04-06 的 SSH 路徑最大的差異。
3. **絕對不要**讓 self-hosted runner 執行來自 fork 的 pull request（見下方細節）。
4. **狀態會殘留**，需要額外的清理紀律（見下方細節）。
5. **GitHub 不會把 job 派給 30 天內沒有更新 runner application 的 runner**——這是治理上的
   一個保護機制，逼你保持 runner 版本更新。

具體來說：

1. **絕對不要**讓 public repo 的 self-hosted runner 執行來自 fork 的 pull request。
   任何陌生人 fork 你的 repo、在 workflow 裡塞一行惡意指令、發一個 PR，那行指令就會在你的內網
   機器上執行。GitHub 的官方文件對這一點有非常明確的警告。參考範本**只用 `workflow_dispatch`**，
   就是為了避免這個風險。

2. **狀態會殘留。** 上一個 job 留下的檔案、環境變數、cache、甚至被竄改的工具，都會影響下一個
   job。託管 runner 每次都是新的，self-hosted 不是。正式環境常見的緩解手段是讓每個 job 跑在
   拋棄式的容器或 VM 中（ephemeral runner）。

3. **它在你的內網裡。** 這既是它的價值，也是它的風險——一旦被利用，攻擊者就取得了一個內網
   立足點。

4. **權限最小化。** runner 的服務帳號不要用 root，只給它完成工作所需的權限。

### E. 概念：runner group 與組織政策

規模一大，就不能讓每個人各自亂裝 runner。企業的治理手段包括：

- **Runner groups** — 把 runner 分組，並限制「哪些 repository 或哪些 workflow 可以使用這一組」。
  例如把能碰 production 的 runner 單獨一組，只開放給特定 repo。
- **組織／企業層級的 Actions 政策** — 限制可以使用哪些 action（例如只允許 GitHub 官方與已驗證
  的建立者、或明確列白名單）、是否允許 fork PR 執行、預設的 `GITHUB_TOKEN` 權限等。
- **Secrets 治理** — 在組織層級集中管理 secrets 並限定可存取的 repo，搭配 environment secrets
  做環境隔離。原則不變：**能用 OIDC 就不要存長期憑證**（本課對應的是 workflow 07 的
  App Service + OIDC 部署路徑；相對地，04-06 的 SSH 路徑就必須自己保管長期私鑰）。

### F. 選修：課後的隔離實驗（不在課程資源上進行）

如果你想在課後親手體驗一次 self-hosted runner 的註冊與執行，**這不是課堂活動**，而且必須完全
與課程資源隔離：

- 需**先取得講師同意**，並使用**另一台你自己、經講師同意的隔離機器**（不是課程 VM）。
- 建立**另一個你自己擁有的獨立 private repository**（不是課程 repo、也不是其 fork）。
- 一切註冊、執行、清理都在那台隔離機器與那個獨立 repo 上進行，**與本課程 VM、班級 repo 完全
  無關**。
- 詳細步驟請直接參考 GitHub 官方文件（見文末連結），本講義**刻意不提供**任何針對課程 VM 或
  課程 repo 的註冊指令。

## 你要自己完成的設計（不需要實跑）

以 [`starters/lab07.yml`](starters/lab07.yml) 為藍本，在你的設計筆記中補齊：

- `runs-on:` 應該寫成哪一個多 label 清單，為什麼？
- 五個安全邊界分別對應到 YAML 或部署流程的哪一部分？
- 如果要把這個模式搬到正式環境，你會怎麼把 runner 與正式主機分開、怎麼設 runner group？

> 這是**設計與觀察**練習，**不需要**、也**不應該**把它註冊到任何 runner 或在課程資源上執行。
> 對照答案：[`solutions/lab07.yml`](solutions/lab07.yml)（唯讀參考）。

## 驗收標準（觀察 / 設計）

- [ ] 你能說出 GitHub 託管 runner 與 self-hosted runner 的責任與環境差異
- [ ] 你能讀懂 `runs-on: [ self-hosted, Linux, X64, gh200 ]` 這種多 label 清單的意義
- [ ] 你能說明這條同機部署路徑**為什麼全程零 inbound SSH**
- [ ] 你**觀察**了講師在私有 `MoneyYu/GH-200` 上的 `08` 執行與 log，並能對照到 log 的內容
- [ ] 你能解釋為什麼本 repo 的 `08` 會被**略過（`if: ${{ false }}`）**，以及為什麼學員 fork
      不應在課程 VM 上註冊 runner
- [ ] 你能完整說出五個安全邊界：同一台 VM 只是課堂簡化、零 inbound SSH、正式環境要分離
      runner 與正式主機、絕不服務不受信任的 fork PR、30 天未更新的 runner 拿不到 job
- [ ] 你能說出「為什麼這個 workflow 不能加上 `pull_request` 觸發」
- [ ] 你能說明 runner group 與組織政策各自控制什麼

## 常見誤解

| 症狀 / 疑問 | 說明 |
|---|---|
| 「為什麼我把 `08` 複製到 fork 也不會跑？」 | 它的 job 是 `if: ${{ false }}`，惰性參考範本，設計上永遠跳過；不要為了讓它跑而註冊 runner 到課程 VM |
| `runs-on: self-hosted, Linux` 直接語法錯 | 多個 label 要寫成清單：`[ a, b, c ]` 或多行 `- ` 清單 |
| 以為 self-hosted runner 每次都是乾淨環境 | 不是；狀態會殘留，正式環境常用 ephemeral runner 緩解 |
| 以為自管 runner「免費」 | 不計 Actions minutes，但 VM、修補、安全與清理成本都在你身上 |
| 想在自己 fork 上實跑一次 | 只能在課後、經講師同意的**隔離機器 + 獨立 private repo** 上做，絕不用課程資源 |

## 參考

- 課堂唯一 live demo：講師在私有 `MoneyYu/GH-200` 上的 `08.selfhosted-runner`（常駐 runner）
- 唯讀參考 YAML：[`starters/lab07.yml`](starters/lab07.yml) ·
  [`solutions/lab07.yml`](solutions/lab07.yml) ·
  [`08.selfhosted-runner.yml`](../.github/workflows/08.selfhosted-runner.yml)
- GitHub 官方文件（課後隔離實驗自行參考）：
  [Self-hosted runners](https://docs.github.com/en/actions/concepts/runners/self-hosted-runners) ·
  [Runner groups](https://docs.github.com/en/actions/concepts/runners/runner-groups)
