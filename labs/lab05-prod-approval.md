# Lab 05 — Promote 到 production（核准關卡）

## 學習目標

做完這個 lab，你應該可以：

- 建立一個**真正的 build once, promote** workflow：只用 `actions:read` 去找出
  workflow 04 已經跑成功的那個 run，下載它的 artifact，**完全不重新 build**
- 說明兩層人工確認的差異：`workflow_dispatch` 的 `confirm` + 40 字元 `build_sha` 明確輸入，
  與 GitHub Environment 的保護規則（protection rules）／required reviewer 各擋住什麼
- 親眼看到 workflow **停在 waiting 狀態等待核准**，並完成核准動作
- 分辨 repository 層級與 environment 層級的 secrets／variables
- 說明「promote」的意義：**部署同一份已驗證的產出，而不是重新建置**
- 驗證 production 服務（port 8081）確實更新為指定的 build SHA

## 對應模組

**Module 5 — Secure and Optimize Automation**（environment protection rules、approval gates、最小權限）

## 前置需求

> **預設由講師實跑。** production 部署會用到 VM 的長期 SSH 私鑰。你仍要自己完成 YAML，
> 並觀察講師示範 run 在 `production` Environment 等待核准的過程。

- 已完成 [Lab 04](lab04-deploy-test.md)，test 環境可以成功部署，且該次 push 對應的
  你自己的 **`lab04-deploy-test.yml`** run 是**成功**狀態（Lab05 要靠 `actions:read` 找到它，不會重新 build）
- 你的 repo 已建立 GitHub Environment：**`production`**，且已設定 **required reviewer**
  - `scripts/setup-student-repo.*` 會建立 environment，但**審核者需要你自己或講師指定**
  - 課堂做法：把你自己設為 reviewer，這樣你可以自己按核准，體驗完整流程
- Variables（`VM_PUBLIC_IP` / `VM_SSH_USER` / `VM_SSH_HOST_KEY`）與 **Environment secret**
  （`VM_SSH_PRIVATE_KEY`，設在 `test` 與 `production` 兩個 Environment，不是 repository secret）同 Lab 04
- 手邊要有 Lab 04 那次 push 的**完整 40 字元 commit SHA**（`git log --format=%H -1`，
  或到該次 workflow 04 run 頁面複製）——待會觸發 Lab05 時要貼上

> ⚠️ VM 的 public IP 一律以 `<VM_PUBLIC_IP>` / `${{ vars.VM_PUBLIC_IP }}` 表示，講師會在課堂上給實際值。

## 步驟

1. 建立 `.github/workflows/lab05-deploy-prod.yml`，從 [`starters/lab05.yml`](starters/lab05.yml) 開始。
   這支 workflow **不 build、不重跑 test 部署**——它只有兩個 job：驗證輸入的 `guard`，
   以及真正部署的 `deploy-prod`。這就是實際課堂使用的 workflow 05 的教學模式：
   **build once, promote**，而不是每個環境各自重來一次。

2. **確認 environment 保護規則。** 到 repo 的設定頁找到 Environments，點開 `production`：
   - 勾選 required reviewers，加入至少一個人（課堂上就是你自己）
   - 觀察這裡還有哪些選項可以設定：等待時間、可部署的分支限制、以及**只屬於這個 environment 的 secrets／variables**

   關鍵觀念：**保護規則是設定在 environment 上，不是寫在 YAML 裡。** YAML 裡只寫 `environment: production` 這一行，剩下的由 repo 設定決定。這樣的分工讓「誰可以放行上線」不會被改 YAML 的人繞過。

3. **加入 `guard` job，驗證兩個手動輸入。** `workflow_dispatch` 要定義兩個 `inputs`：
   - `confirm`：必須手動輸入 `deploy` 這個字，才代表「我真的要部署正式環境」
   - `build_sha`：要 promote 的**完整 40 字元 commit SHA**（小寫十六進位）

   `guard` job 要用 shell 驗證這兩個輸入：
   ```bash
   if [ "$CONFIRM" != "deploy" ]; then echo "..." >&2; exit 1; fi
   if ! printf '%s' "$REQUESTED_SHA" | grep -Eq '^[0-9a-f]{40}$'; then echo "..." >&2; exit 1; fi
   ```
   這是**第一層**人工確認：擋手滑誤觸，以及格式錯誤（例如貼到縮寫的 7 碼 SHA）。
   第二層是 environment 的 required reviewer——兩層互補，缺一不可。

4. **加入 `deploy-prod` job，只用 `actions:read` 去找 artifact，不重新 build。**
   - `needs: guard`：沒通過輸入驗證就不可能往下跑
   - `permissions: { actions: read }`：**這是全 job 唯一需要的權限**，只用來讀 run 清單與下載 artifact，
     不需要 `contents:write`、不需要 Azure 憑證、不需要任何寫入權限
   - 用 `gh run list --workflow lab04-deploy-test.yml --commit "$BUILD_SHA" --status success` 找出
     `headSha` 等於 `build_sha` 且狀態成功的那個 run，取得它的 run id
     （這是**你自己**放進 `.github/workflows/lab04-deploy-test.yml` 的 Lab04 run，不是講師的 `04.deploy-test.yml`）
   - 用 `gh run download <run_id> --name simpleweb-jar --dir dist` 下載**那個 run**產生的 artifact
   - 找不到成功的 run，或該 run 沒有 `simpleweb-jar` artifact，都要直接 `exit 1`——**絕對不能**退而求其次自己重新 build

   這正是「promote（晉升）」的意思：**不重新建置，把已經在 test 驗證過的那一份原封不動送上 production。**
   重新 build 一次會產出不同的二進位檔（即使原始碼一樣，build 時間戳記也不同），等於 test 驗證的東西和上線的東西不是同一個。

   | 項目 | test（workflow 04） | production（Lab05） |
   |---|---|---|
   | `environment.name` | `test` | `production` |
   | systemd service | `simpleweb-test` | `simpleweb-prod` |
   | jar 目錄 | `/opt/simpleweb/test` | `/opt/simpleweb/prod` ← **注意是 `prod` 不是 `production`** |
   | port | 8080 | 8081 |

   SSH 的寫法（釘選指紋、`umask 077`、`if: always()` 清私鑰）與 workflow 04 完全相同。

5. **（建議）加上上線前備份。** 在覆蓋 `/opt/simpleweb/prod/simpleweb.jar` 之前，先複製一份 `simpleweb.jar.previous`。真實世界的部署腳本幾乎都會這麼做，出事時可以快速回復。


6. **Smoke test 改打 8081，比對 `build_sha` 輸入值。** 注意：這裡驗證的基準是**你觸發時輸入的
   `inputs.build_sha`**，不是 `github.sha`（因為這支 workflow 本身可能跑在任何 commit 上觸發，
   真正要驗的是「promote 的是不是你指定的那個 SHA」）。

7. **手動觸發，帶上 `confirm` 與 `build_sha`，然後觀察核准流程。** 這是本 lab 的重點，請放慢看：
   - `guard` 綠了（代表兩個輸入都通過驗證）
   - `deploy-prod` **不會**開始跑，它會停在等待狀態，run 頁面上出現需要審核的提示
   - 同時，被指定為 reviewer 的人會收到通知
   - 注意：這段等待時間**不會**消耗 runner，因為根本還沒有 runner 被指派

8. **執行核准。** 在 run 頁面上點擊審核的按鈕，可以選擇核准或拒絕，並留下一段註解。核准後 `deploy-prod` 才會開始執行，先去找 workflow 04 的成功 run、下載 artifact，再部署。
   - 順便試一次「拒絕」：拒絕後 job 會顯示為失敗／已取消，整條流程停在這裡。做完再重跑一次並核准。

9. **驗證 production。**
   - `curl http://<VM_PUBLIC_IP>:8081/actuator/health` → 200
   - 瀏覽器開 `http://<VM_PUBLIC_IP>:8081/`，environment 應顯示 **production**
   - 同時開 `:8080`（test）比較，兩者是**同一台 VM 上的兩個服務**，port 與 environment 都不同

10. **確認稽核軌跡。** 回到 Environments 設定頁，`production` 會列出部署歷史；每一筆都能追到是誰核准、部署了哪個 commit。這就是企業要 environment 的主要理由：**可追溯**。

## 概念補充：environment-scoped secrets

同一個 secret 名稱可以在不同層級各存一份，取用時的優先順序是：

```
environment secret  >  repository secret  >  organization secret
```

實務上非常有用：例如把只有 production 才用得到的憑證放在 `production` environment，
而 YAML 完全不用改。**沒寫 `environment:` 的 job 永遠拿不到 environment secrets。**
這是很重要的隔離機制。

## 你要自己完成的 YAML

Starter：[`starters/lab05.yml`](starters/lab05.yml)

```yaml
on:
  workflow_dispatch:
    inputs:
      confirm:
        description: 輸入 deploy 以確認要部署到正式環境
        required: true
        default: ''
      build_sha:
        description: 要 promote 的完整 40 字元 commit SHA
        required: true

permissions: {}

jobs:
  guard:
    runs-on: ubuntu-latest
    steps:
      # TODO: 驗證 inputs.confirm == "deploy"，否則 exit 1
      # TODO: 驗證 inputs.build_sha 符合 ^[0-9a-f]{40}$，否則 exit 1

  deploy-prod:
    runs-on: ubuntu-latest
    # TODO: needs: guard
    # TODO: permissions: actions: read   ← 最小權限，只用來讀 run 清單、下載 artifact
    # TODO: environment: name production + url（port 8081）
    steps:
      # TODO: gh run list --workflow lab04-deploy-test.yml --commit <build_sha> --status success
      #       找出成功的 run id，再 gh run download <run_id> --name simpleweb-jar --dir dist
      #       （不要重新 build！找不到就直接失敗，不要退而求其次自己 build）
      # TODO: 用和 workflow 04 一樣的 SSH 設定（釘選指紋 + umask 077 + always 清私鑰）
      # TODO: scp + ssh 部署到 /opt/simpleweb/prod，restart simpleweb-prod
      #       （加分：覆蓋前備份成 simpleweb.jar.previous）
      # TODO: smoke test :8081/api/info，驗 buildSha == inputs.build_sha（不是 github.sha）
```

## 驗收標準

- [ ] `deploy-prod` job 在核准前**確實停住**，run 頁面顯示等待審核
- [ ] 你完成了一次核准，`deploy-prod` 才開始執行
- [ ] `guard` 與 `deploy-prod` 最終全部**綠色勾勾**
- [ ] `curl http://<VM_PUBLIC_IP>:8081/actuator/health` **回傳 200**
- [ ] 瀏覽器開 `:8081`，environment 顯示 **production**；開 `:8080`，顯示 **test**
- [ ] production 顯示的 build SHA 等於你輸入的 `build_sha`
- [ ] `deploy-prod` 部署的是用 `actions:read` 從 workflow 04 成功 run 下載的同一份 jar（**沒有重新 build，也沒有重新部署 test**）
- [ ] `production` environment 的部署歷史中有這一筆紀錄，含核准者
- [ ] 你能說出：為什麼保護規則設在 environment 而不是寫在 YAML 裡
- [ ] 你能說出：為什麼 `deploy-prod` 只給 `actions:read`，不給 `contents:write` 或其他寫入權限
- [ ] 你能說出：為什麼 `confirm` + `build_sha` 這兩層輸入驗證，和 environment 的 required reviewer 互不取代

## 常見錯誤

> Production 解答沿用 Lab 04 的 fail-closed 模式：smoke test 比對 `/api/info` 的 build SHA。
> 若舊服務仍健康但新部署失敗，workflow 必須保持紅燈。

| 症狀 | 原因 | 修法 |
|---|---|---|
| `deploy-prod` 直接就跑了，沒有停下來 | environment 名稱拼錯，或該 environment 沒設 required reviewer | 確認是 `production`（不是 `prod`）且已加審核者 |
| 核准按鈕沒出現／按不下去 | 你不在 reviewer 名單裡 | 把自己加進 required reviewers |
| `guard` 就直接失敗 | `confirm` 沒輸入 `deploy`，或 `build_sha` 不是 40 字元小寫十六進位 | 觸發時把兩個欄位都填對；短版 SHA（7 碼）不合格 |
| 找不到成功的 workflow 04 run | 輸入的 `build_sha` 對應的 push 還沒跑過 workflow 04，或那次 run 失敗了 | 先確認 Lab04 那次 push 的 workflow 04 是綠燈，再拿它的完整 SHA 來 promote |
| `gh run download` 失敗說沒有 artifact | workflow 04 run 沒有成功上傳 `simpleweb-jar`（例如 build 失敗在上傳之前） | 換一個真的有上傳成功的 run；不要自己另外 build 一份頂替 |
| 部署到了 `/opt/simpleweb/production/` | jar 目錄填成 `production` | 目錄是 `prod`，environment 名稱才是 `production` |
| 重啟了 `simpleweb-test` 卻說是上 prod | service 名稱沒改 | 遠端腳本要 restart `simpleweb-prod` |
| prod 顯示 environment = test | 部署到了 test 目錄/service，或 systemd prod unit 的 `Environment=APP_ENVIRONMENT=production` 錯誤 | 檢查目標目錄、service 名稱與 unit |
| `Host key verification failed` | `known_hosts` 指紋不符 | 確認 `vars.VM_SSH_HOST_KEY` 對應這台 VM |
| smoke test 打 8080 都過，8081 不通 | port 沒改，或 prod 服務沒起來 | 檢查 `systemctl is-active simpleweb-prod` 的輸出 |
| 抓不到 environment secret | job 沒寫 `environment:` | 只有綁定 environment 的 job 才拿得到 |
| 等待核准時擔心在燒分鐘數 | 誤解 | 等待期間沒有 runner 被佔用 |

## 解答

[`solutions/lab05.yml`](solutions/lab05.yml)
