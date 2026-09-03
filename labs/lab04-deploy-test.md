# Lab 04 — 部署到 test 環境（SSH 到 on-prem VM）

## 學習目標

做完這個 lab，你應該可以：

- 使用 `environment:` 把 job 綁定到 GitHub Environment，並在 Actions 頁面看到部署連結
- 把 build 與 deploy 拆成兩個 job：build job **不接觸 SSH 私鑰**，deploy job 才拿到憑證
- 用 `upload-artifact` / `download-artifact` 在 job 之間交接 jar，**不需要 release、也不需要 public repo**
- 用 **SSH（`scp` + `ssh`）** 把 jar 推到模擬 on-prem 的 Ubuntu VM，並重啟 systemd 服務
- 正確地釘選主機指紋（`vars.VM_SSH_HOST_KEY`）而不是用 `ssh-keyscan` 盲目信任首次連線
- 說出 SSH 部署的成本：要保管長期私鑰、要開放來源受控的 TCP/22、要負責金鑰輪替
- 理解 build metadata（commit SHA / build 時間）是在 **build 時烤進 jar** 的，部署端不再改寫
- 用 smoke test 驗證部署結果——比對 `/api/info` 的 `buildSha`，而不是「看起來綠色就當作成功」

## 對應模組

**Module 1 — Design and Manage Workflows**（secrets、變數、job 相依）
**Module 5 — Secure and Optimize Automation**（最小權限、主機指紋釘選、憑證清理、信任邊界）

## 前置需求

> **預設由講師實跑。** VM 的 SSH 私鑰是長期憑證，且每個 fork 都需要講師配置；未獲授權時，
> 請完成 YAML 並對照講師的實際 workflow log，不要要求或共用長期私鑰。

- 已完成 [Lab 03](lab03-package-artifact.md)
- 你的 repo 已建立 GitHub Environment：**`test`**（`scripts/setup-student-repo.*` 會幫你建）
- 你的 repo 已設定下列 **variables**（講師提供實際值）：
  - `VM_PUBLIC_IP` ← **課堂上才會拿到，講義中一律寫 `<VM_PUBLIC_IP>`**
  - `VM_SSH_USER` ← VM 上的部署帳號
  - `VM_SSH_HOST_KEY` ← VM 的 SSH 主機公鑰（釘選用的完整 `known_hosts` 條目）
- 你的 repo 已設定下列 **Environment secret**（講師提供；分別設在 `test` 與 `production` 兩個
  Environment，**不要**放成 repository secret）：
  - `VM_SSH_PRIVATE_KEY` ← 部署用的 SSH 私鑰。它可 sudo、又是長期憑證，設成 Environment
    secret 後只有綁定 `test`／`production` 的 job 讀得到，任意分支的 job 讀不到

> ⚠️ **VM 的 public IP 目前未知。** 本講義所有地方都以 `<VM_PUBLIC_IP>` 或 `${{ vars.VM_PUBLIC_IP }}` 表示，講師會在課堂上公布實際 IP，請把它設定成 repository variable，**不要**寫死在 YAML 裡。

### 目標環境契約（不可更動）

| 環境 | systemd service | Port | Jar 路徑 |
|---|---|---|---|
| test | `simpleweb-test` | 8080 | `/opt/simpleweb/test/simpleweb.jar` |
| production | `simpleweb-prod` | 8081 | `/opt/simpleweb/prod/simpleweb.jar` |

systemd unit 直接用 `Environment=` 設定 `SERVER_PORT` / `APP_ENVIRONMENT`。
build metadata（`buildSha` / `buildTime`）**不是**執行期環境變數，而是在 build 時由
Maven resource filtering 烤進 jar，由應用程式直接從 classpath 讀出，所以部署端
**不需要、也不應該**再寫任何 `APP_BUILD_*` 檔案。

## 步驟

1. 建立 `.github/workflows/lab04-deploy-test.yml`，從 [`starters/lab04.yml`](starters/lab04.yml) 開始。

2. **先把 build 與 deploy 拆成兩個 job。** 這是本 lab 的設計重點：

   > **build job 會執行 repo 裡的建置指令（`./mvnw`），而這些指令可能被任何有 push 權限的人改動。**
   > 如果 build job 同時握有 SSH 私鑰，一旦建置腳本被動手腳，私鑰就可能外洩。
   > 所以 build job 只負責產出並上傳 artifact；**只有 deploy job 才注入 `VM_SSH_PRIVATE_KEY`。**

3. **build job：烤入版本並上傳 artifact。**
   - `checkout` 時加上 `persist-credentials: false`（build job 不需要 git 認證留在 runner 上）
   - build 指令帶上版本資訊，把完整 commit SHA 與 UTC 時間烤進 jar：
     ```bash
     ./mvnw -B verify -Dapp.build.sha="$GITHUB_SHA" -Dapp.build.time="$(date -u +%FT%TZ)"
     ```
   - 用 `actions/upload-artifact` 上傳 `target/simpleweb.jar`（名稱 `simpleweb-jar`）。
     這是特權部署 workflow，`uses:` 要**釘選成不可變的 40-hex commit SHA**（原 tag `@v7`；
     實際 SHA 見 [`README.md` 的「使用的 action 版本」](README.md#使用的-action-版本)），
     不要把可變 tag 複製進來

   > **為什麼用 artifact，而不是 release？**
   > jar 只需要在同一次 workflow 的兩個 job 之間傳遞，Actions artifact 就夠了。
   > 這條路徑不需要把 repo 設成 public，也不需要在 VM 上開對外下載通道——
   > 我們改用 SSH 主動把檔案推上去。

4. **deploy job：綁定 environment 並釘選 SSH 身分。**
   - `needs: build`
   - `environment:` — `name: test`，`url:` 指向 `http://${{ vars.VM_PUBLIC_IP }}:8080/`
   - 先 `download-artifact` 取回 jar
   - 把私鑰與主機公鑰寫進 `$RUNNER_TEMP` 下的暫存目錄，**寫入前先 `umask 077`**，讓檔案權限是 0600：
     ```bash
     ssh_dir="$RUNNER_TEMP/simpleweb-ssh"; mkdir -p "$ssh_dir"; umask 077
     printf '%s\n' "$SSH_PRIVATE_KEY" > "$ssh_dir/id_deploy"
     printf '%s\n' "$SSH_HOST_KEY"    > "$ssh_dir/known_hosts"
     ```

   > 🔒 **釘選主機指紋，不要用 `ssh-keyscan`。**
   > `ssh-keyscan` 會信任「第一次連到的那把公鑰」，等於把中間人攻擊的機會交給網路。
   > 正確做法是講師事先把 VM 的主機公鑰放進 `vars.VM_SSH_HOST_KEY`，我們寫成
   > `known_hosts` 後強制 `StrictHostKeyChecking=yes`。

5. **推 jar、重啟服務。** `scp` 上傳、`ssh` 執行安裝與重啟。所有 `ssh` / `scp` 都要帶上這組選項：
   ```
   -o StrictHostKeyChecking=yes
   -o UserKnownHostsFile=<剛剛寫的 known_hosts>
   -o GlobalKnownHostsFile=/dev/null
   -o BatchMode=yes
   -o ConnectTimeout=15
   ```
   - **destination（`user@host`）放在 `--` 之後**，避免以 `-` 開頭的值被當成選項
   - 先驗證 `VM_SSH_USER` 與 `VM_PUBLIC_IP` 的格式再拼進指令
   - 遠端腳本用 `set -euo pipefail`，並 `trap` 清掉暫存 jar
   - `install -o simpleweb -g simpleweb -m 0644` 到 `/opt/simpleweb/test/simpleweb.jar`，
     再 `systemctl restart simpleweb-test` 並 `systemctl is-active` 確認

6. **一定要清掉私鑰。** 加一個 `if: always()` 的 step `rm -f` 掉暫存私鑰，減少殘留在 runner 上的時間。

7. **Smoke test —— 而且必須「驗到版本」。** 部署完不要相信「綠色 = 成功」：
   - 對 `http://${{ vars.VM_PUBLIC_IP }}:8080/api/info` 做 `curl -fsS`
   - 從 JSON 取出 `buildSha`，**必須等於這次的 `github.sha`** 才算成功
   - 服務啟動需要時間，請用迴圈重試（建議 12 次、每次間隔 5 秒），全部失敗就 `exit 1`

   > 只檢查 HTTP 200 是不夠的：舊版本還活著時一樣回 200，會讓失敗的部署變成假的綠燈。

8. **Push 並觀察。** 在 run 頁面你應該看到 `build` job 完成後 `deploy-test` job 標示著 environment `test`，完成後出現指向 `http://<VM_PUBLIC_IP>:8080/` 的連結。

9. **用瀏覽器驗證。** 開啟 `http://<VM_PUBLIC_IP>:8080/`，頁面上應顯示 environment 為 `test`、以及這次的 build SHA。再看 `http://<VM_PUBLIC_IP>:8080/api/info` 的 JSON。

### 對照：為什麼還要學 07 的 App Service + OIDC？

SSH 直覺、好懂，但你必須保管一把長期私鑰、開放來源受控的 TCP/22，還要自己輪替金鑰。
**講師示範的對照 workflow [`07.deploy-webapp.yml`](../.github/workflows/07.deploy-webapp.yml)**
改用 Azure App Service + OIDC：GitHub 每次執行換發短期 token，不保存任何 Azure 長期密碼。
這是**講師端的 PaaS/OIDC 對照示範，不是 Lab 07 的學員實作**（Lab 07 練的是 self-hosted
runner 的同機部署）。兩者對照，就能理解「PaaS + 聯合身分」在正式環境常常是更省心的選擇。

## 你要自己完成的 YAML

Starter：[`starters/lab04.yml`](starters/lab04.yml)

```yaml
permissions:
  contents: read          # workflow 層級：最小

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      # checkout（persist-credentials: false）/ setup-java — uses 一律釘選 40-hex SHA
      # TODO: ./mvnw -B verify -Dapp.build.sha / -Dapp.build.time
      # TODO: upload-artifact（釘選 SHA，原 tag @v7；名稱 simpleweb-jar）

  deploy-test:
    runs-on: ubuntu-latest
    # TODO: needs: build
    # TODO: environment: name test + url（port 8080）
    steps:
      # TODO: download-artifact（釘選 SHA，原 tag @v7）
      # TODO: 寫入私鑰 + 釘選 known_hosts（umask 077）
      # TODO: scp + ssh 部署到 /opt/simpleweb/test，restart simpleweb-test
      # TODO: if: always() 清掉私鑰
      # TODO: smoke test，驗 /api/info 的 buildSha == github.sha
```

## 驗收標準

- [ ] `build` job 上傳了名為 **`simpleweb-jar`** 的 artifact，`deploy-test` job 成功下載
- [ ] `build` job 的步驟中**沒有**注入 `VM_SSH_PRIVATE_KEY`（私鑰只出現在 deploy job）
- [ ] Actions run 中 `deploy-test` job 顯示**綠色勾勾**，且標示 environment `test`
- [ ] SSH 相關的 step 都使用了 `StrictHostKeyChecking=yes` 與釘選的 `UserKnownHostsFile`
- [ ] 清理私鑰的 step 帶有 `if: always()`
- [ ] smoke test step 成功，`curl` 對 `:8080/api/info` 取得的 `buildSha` **等於這次 commit SHA**
- [ ] 瀏覽器開 `http://<VM_PUBLIC_IP>:8080/`，頁面顯示 **environment = test**
- [ ] job 頁面上出現指向 test 環境的連結

## 常見錯誤

> **控制平面綠燈不等於部署成功。** systemd `restart` 成功不代表新版本真的在跑。
> 解答的 smoke test 會比對 `/api/info` 的 `buildSha` 是否為本次 commit，
> 舊版本仍健康但新部署失敗時，workflow 必須保持紅燈。

| 症狀 | 原因 | 修法 |
|---|---|---|
| `Host key verification failed` | `known_hosts` 沒寫對，或指紋和 VM 不符 | 確認 `vars.VM_SSH_HOST_KEY` 是這台 VM 的完整主機公鑰條目 |
| `Permission denied (publickey)` | 私鑰不對、或 VM 上沒授權這把公鑰 | 確認 `secrets.VM_SSH_PRIVATE_KEY` 與 VM 的 `authorized_keys` 相符 |
| SSH 卡住不動 | 少了 `BatchMode=yes` / `ConnectTimeout`，卡在互動提示 | 補上這兩個選項 |
| `scp` 說 destination 無效 | user/host 以 `-` 開頭被當成選項 | destination 放在 `--` 之後，並先驗證格式 |
| Build SHA 仍是舊值 | jar 沒真的被覆蓋，或 service 沒 restart | 看 log 確認 `install` 與 `restart` 都跑到；讀 `journalctl -u simpleweb-test` |
| `systemctl restart` permission denied | 遠端指令沒有用 `sudo` | 確認遠端腳本內的安裝／重啟都有 `sudo` |
| smoke test 一直失敗但服務其實有起來 | 網路安全群組沒開 8080，或 IP 填錯 | 確認 `VM_PUBLIC_IP`；請講師確認 NSG |
| smoke test 秒失敗 | 沒有等服務啟動 | 加上重試迴圈與 `sleep` |
| SSH 連不上 VM | VM 關機、或 TCP/22 未對 GitHub runner 開放 | 請講師確認 VM 電源與 `AllowSshFromAzureCloud` 規則；**切勿把 22 對整個 Internet 開放** |

## 解答

[`solutions/lab04.yml`](solutions/lab04.yml)
