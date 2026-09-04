# Lab 06 — 讀 log 除錯（四個壞掉的 workflow）

> 這是整份講義**最重要**的一個 lab。前面五個 lab 教你「怎麼寫對」，
> 這個 lab 教你「寫錯的時候怎麼自己救回來」——那才是你回到公司後每天要做的事。

## 學習目標

做完這個 lab，你應該可以：

- 在 Actions 頁面快速定位到「哪一個 job、哪一個 step、哪一行」出錯
- 讀懂 GitHub Actions 常見的四類錯誤訊息並自行修正
- 分辨「YAML 語法錯（根本沒跑）」與「執行期錯（跑了但失敗）」
- 用 `ACTIONS_STEP_DEBUG` / `ACTIONS_RUNNER_DEBUG` 打開 debug log
- 使用 re-run failed jobs 只重跑失敗的部分，縮短除錯循環
- 知道「未定義的 expression 會變成空字串」這個致命陷阱

## 對應模組

**Module 2 — Consume and Troubleshoot Workflows**（讀 log、debug logging、診斷失敗、workflow templates）

## 前置需求

- 已完成 Lab 01 ~ Lab 04（broken-3 是獨立的 OIDC 權限題，需要 Azure secrets 才能實跑；
  OIDC 在本課對應的是 workflow 07 的 App Service 部署路徑。若你的環境還沒設定好，可以只讀 log 訊息並推理）
- 建議在 repo 開一個新分支做這個 lab，避免污染 `main`

## 步驟

### 通則：定位失敗的三步驟

每次 workflow 變紅，都用同樣的順序找：

1. **哪個 job？** run 頁面左側的 job 清單，紅色 X 的那一個。
2. **哪個 step？** 點進 job，展開的 step 清單中紅色的那一個。GitHub 通常會自動幫你展開失敗的 step。
3. **哪一行？** 在該 step 的 log 中往上找**第一個**錯誤訊息。
   > ⚠️ 新手最常犯的錯：只看 log 最後一行。最後一行常常只是「Process completed with exit code 1」這種沒有資訊量的結論，**真正的原因在更上面**。

技巧：
- log 右上角有搜尋功能，直接搜 `error`、`ERROR`、`Exception`、`No such file`
- 每一行 log 都可以取得永久連結，回報問題時直接貼那個連結，比截圖有用
- 整個 job 的原始 log 可以下載成文字檔，用你熟悉的編輯器搜尋

### 練習：四個壞掉的 workflow

把 `starters/lab06-broken-1.yml` ~ `broken-4.yml` **一個一個**複製到 `.github/workflows/`（一次只放一個，避免互相干擾），push 後觀察並修正。

修正時請填寫 [`starters/lab06.yml`](starters/lab06.yml) 這份除錯記錄表——**寫下你怎麼推理出答案的**，比改對更重要。

---

#### Case 1 — 縮排錯誤 / `steps` 不是清單

檔案：[`starters/lab06-broken-1.yml`](starters/lab06-broken-1.yml)

**症狀（這一個和其他三個不同，請特別注意）：**
push 之後，Actions 頁面上**看不到任何執行紀錄**。因為這份 YAML 根本不合法，GitHub 連 parse 都失敗，自然不會產生 run。

**怎麼找：**
- 到 repo 裡打開這個 workflow 檔案，GitHub 會直接標示 `Invalid workflow file` 並指出行號
- 或看該 commit，會有一個紅色標記
- 訊息形如 `You have an error in your yaml syntax on line N`

**你要想清楚的觀念：**
`steps:` 的值是一個**清單（sequence）**，每個項目以 `- ` 開頭；同一個 step 裡的 `name` / `uses` / `with` / `run` 必須對齊在 `- ` 之後的同一欄。少縮排兩格，YAML 就會認為你在關閉清單，然後炸掉。

**任務：** 修好縮排，讓它能被 GitHub 接受並成功執行。不要整份重寫。

---

#### Case 2 — 少了 `actions/checkout`

檔案：[`starters/lab06-broken-2.yml`](starters/lab06-broken-2.yml)

**症狀：** workflow 有跑，`Set up JDK 21` 是綠的，`Build` 是紅的。

**怎麼找：** 展開 `Build` step，你會看到類似
```
./mvnw: No such file or directory
```
或
```
The goal you specified requires a project to execute but there is no POM in this directory
```

**你要想清楚的觀念：**
runner 是一台**全新的空機器**。它知道你的 repo 是哪一個，但**不會**自動把程式碼放上去。`actions/checkout` 就是做這件事的。

**任務：** 用一行修好它。

---

#### Case 3 — 少了 `permissions: id-token: write`

檔案：[`starters/lab06-broken-3.yml`](starters/lab06-broken-3.yml)

**症狀：** `Azure login (OIDC)` step 失敗，訊息類似
```
Unable to get ACTIONS_ID_TOKEN_REQUEST_URL env variable
```
或其他 OIDC / federated credential 相關錯誤。

**怎麼找：** 錯誤訊息裡出現 `ID_TOKEN` 字樣時，九成是權限問題，不是帳號密碼問題。

**你要想清楚的觀念：**
OIDC 的流程是「workflow 先向 GitHub 索取一個 id token，再拿去換 Azure 權杖」。只有當 job 具備 `id-token: write` 權限時，runner 才會被注入索取 token 所需的環境變數（`ACTIONS_ID_TOKEN_REQUEST_URL` / `ACTIONS_ID_TOKEN_REQUEST_TOKEN`）。沒有這個權限，第一步就過不了。

**另一個陷阱：** job 層級的 `permissions:` 會**整組覆蓋** workflow 層級的設定。只寫 `id-token: write` 的話，`contents: read` 就沒了。需要的權限要一次列齊。

**任務：** 補上正確的 `permissions:`。

> 🔑 **關於這題用的身分（重要）：** 修正版 `azure/login` 的 `client-id` 引用的是
> `secrets.AZURE_WEBAPP_CLIENT_ID`——一個**講師擁有（instructor-owned）、只在受保護的
> upstream `main`（protected main）才實際能登入**的 Web App 專屬身分（與 workflow 07 相同）。
> 你在自己的 **fork 不會拿到這個 Azure 身分**，所以就算補齊了 `id-token: write`，`azure/login`
> 仍可能因為 fork 沒有可用的 federated identity 而無法真正登入——**這題要你診斷的就是
> `id-token: write` 這個權限失敗本身**，不是要你在 fork 上登入成功。已除役的舊
> `AZURE_CLIENT_ID` 共用身分**不是**這題的有效 live identity，別把它當成能登入的帳號。
> 實際的 Azure 登入由講師在 protected main（App Service + OIDC 的 workflow 07）示範。

---

#### Case 4 — 引用了不存在的 job output

檔案：[`starters/lab06-broken-4.yml`](starters/lab06-broken-4.yml)

**症狀：** `build` job 是綠的，`package` job 的 `Download jar` step 失敗，訊息類似
```
Provided artifact name input during validation is empty
```
或找不到指定名稱的 artifact。

**怎麼找：**
1. 看失敗的 step 收到的輸入參數是什麼
2. 你會發現 `name:` 是**空的**
3. 往回追 `${{ needs.build.outputs.jar-name }}`
4. 打開 `build` job，發現它**根本沒有宣告 `outputs:`**

**你要想清楚的觀念（本課最容易踩的陷阱）：**

> **GitHub Actions 引用不存在的 context 欄位時，不會報錯，只會安靜地變成空字串。**

所以錯誤總是延後爆炸在下游，而且訊息看起來八竿子打不著。以後只要看到「某個值莫名其妙是空的」，就往上追它的來源。

job outputs 需要兩件事一起做：
1. 產生值的 step 要有 `id:`，並把值寫進 `$GITHUB_OUTPUT`（或使用 action 自己的 outputs）
2. 在 job 層級宣告 `outputs:`，把 step 的輸出往上拋

**任務：** 讓 `needs.build.outputs.jar-name` 真的有值。

---

### 打開 debug logging

預設的 log 已經很多，但看不到 action 收到的輸入參數、expression 求值結果這些細節。要打開更詳細的輸出，在你的 repo 建立兩個 **repository secret**（值都填 `true`）：

| Secret 名稱 | 效果 |
|---|---|
| `ACTIONS_STEP_DEBUG` | 每個 step 多印出 `##[debug]` 訊息：輸入參數、expression 求值、環境變數 |
| `ACTIONS_RUNNER_DEBUG` | 產生 runner 的診斷 log，可從 run 頁面下載完整 log 壓縮檔 |

設定完之後，**重跑** Case 4，你會直接在 debug 訊息中看到 `download-artifact` 收到的 `name` 是空字串——不需要靠猜。

> 課後記得把這兩個 secret 移除。debug log 會把大量內部細節寫進 log，在正式專案中長期開啟並不恰當。

### 只重跑失敗的 job

在 run 頁面上，重跑有幾種選擇：

| 方式 | 什麼時候用 |
|---|---|
| Re-run all jobs | 想從頭完整驗證整條流程 |
| **Re-run failed jobs** | **除錯時的預設選擇**——沿用先前成功 job 的結果與 artifact，省時間也省執行分鐘數 |
| Re-run 時勾選啟用 debug logging | 只想針對這一次拿到詳細 log，不必新增 secret |
| 單一 job 旁的重跑 | 只重跑那一個 job |

除錯循環的正確姿勢：**改一件事 → 只重跑失敗的 job → 看 log → 再改一件事。** 一次改五個地方然後全部重跑，你永遠不會知道是哪一個修好的。

### 順帶一提：workflow templates

當你在 repo 的 Actions 頁面建立新 workflow 時，GitHub 會依照 repo 內容推薦一批範本（例如 Java with Maven）。組織也可以在特定的 `.github` repo 中放置自訂範本，讓全公司共用一致的起手式。範本是**起點**不是終點——套用後仍要依你的專案調整版本、指令與權限。

## 你要自己完成的 YAML

- 四個修正版：修改你剛複製到 `.github/workflows/` 的檔案；保留
  `starters/lab06-broken-N.yml` 原始題目，方便重做與比較。
- 除錯記錄表：[`starters/lab06.yml`](starters/lab06.yml)，四個 case 的
  `where_i_saw_it` / `error_message` / `root_cause` / `fix` 都要填，
  外加 `debug_logging` 與 `rerun` 兩段。

```yaml
findings:
  - case: broken-1
    where_i_saw_it: TODO
    error_message: TODO
    root_cause: TODO
    fix: TODO
  # ... broken-2 / 3 / 4

debug_logging:
  secrets_i_added: [ TODO, TODO ]
  what_i_learned: TODO

rerun:
  method_used: TODO
  why: TODO
```

## 驗收標準

- [ ] Case 1 修好後，Actions 頁面**出現**這個 workflow 的執行紀錄且為綠色
- [ ] Case 2 修好後，`Build` step 成功並出現 `BUILD SUCCESS`
- [ ] Case 3：你能指出根因是**缺少 `id-token: write`**（沒有它，runner 拿不到索取 OIDC
      token 所需的環境變數，`azure/login` 第一步就失敗），且修正後的 YAML 只加上**最小必要**的
      `permissions: { contents: read, id-token: write }`。修正版的 `client-id` 引用
      **講師擁有、只在 protected main 才能登入**的 `secrets.AZURE_WEBAPP_CLIENT_ID`；**不需要**在
      未配置的學員 fork 上真的登入成功（fork 不會取得該 Azure 身分，只診斷 `id-token: write`
      失敗）——實際的 Azure 登入由講師用已設定好的 workflow 07（App Service + OIDC）示範
- [ ] Case 4 修好後，`package` job 成功下載到 `downloaded/simpleweb.jar`
- [ ] 你已建立 `ACTIONS_STEP_DEBUG`，並能指出 log 中至少一則 `##[debug]` 訊息
- [ ] 你至少使用過一次 **re-run failed jobs**
- [ ] 除錯記錄表四個 case 都填完，且 `root_cause` 是你自己的話
- [ ] 你能用一句話說明「未定義的 expression 會發生什麼事」

## 常見錯誤

| 症狀 | 原因 | 修法 |
|---|---|---|
| 只看 log 最後一行，找不到原因 | 最後一行通常只是 exit code | 往上找**第一個** error |
| 修好了但 Actions 還是紅的 | 看的是舊的 run | 確認是最新那一次 run |
| Case 1 push 後什麼都沒發生，以為 push 失敗了 | YAML 不合法就不會產生 run | 直接打開檔案看 GitHub 的語法標示 |
| 一次貼上四個 broken 檔案，畫面一團亂 | 同時觸發多個 workflow | 一次只放一個 |
| 加了 debug secret 但 log 沒變 | secret 是加在別的 repo，或名稱拼錯 | 名稱要完全一致、值填 `true`，並**重跑**一次 |
| 一次改很多地方，不知道哪個有效 | 沒有控制變因 | 一次只改一件事 |
| 把 `permissions:` 加在 step 上 | `permissions` 只能放在 workflow 或 job 層級 | 移到正確層級 |

## 解答

- 除錯記錄表參考答案：[`solutions/lab06.yml`](solutions/lab06.yml)
- 四個修正版 workflow：
  [`solutions/lab06-fixed-1.yml`](solutions/lab06-fixed-1.yml) ·
  [`solutions/lab06-fixed-2.yml`](solutions/lab06-fixed-2.yml) ·
  [`solutions/lab06-fixed-3.yml`](solutions/lab06-fixed-3.yml) ·
  [`solutions/lab06-fixed-4.yml`](solutions/lab06-fixed-4.yml)
