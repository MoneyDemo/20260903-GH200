# SimpleWeb（GH-200 示範應用程式）

這是 **GH-200 GitHub Actions** 課程使用的 Java 示範網站。它刻意寫得很小，因為課程的主角是
GitHub Actions workflow，不是這支應用程式。

它做的事只有一件：**用一個超大的顏色橫幅告訴你「我是哪個環境、我是哪一版」**，
所以只要打開瀏覽器，全班就能立刻確認剛剛那次部署到底有沒有生效。

## 課程入口

- **學員練習手冊**：[labs/README.md](labs/README.md)
- **Workflow 範例**：[.github/workflows/](.github/workflows/)
- **Test 環境**：`http://<VM_PUBLIC_IP>:8080`（實際 IP 由講師於課堂公布）
- **Production 環境**：`http://<VM_PUBLIC_IP>:8081`

### 漸進式 Workflow

| No. | Workflow | 學習重點 |
| --- | --- | --- |
| 01 | `01.build.yml` | 第一個 workflow、trigger、job、step |
| 02 | `02.build-test.yml` | CI、測試、log、job summary |
| 03 | `03.package-artifact.yml` | `needs`、artifact、job 間傳檔 |
| 04 | `04.deploy-test.yml` | SSH 部署到 on-prem VM 的 test 環境 |
| 05 | `05.deploy-prod.yml` | GitHub Environment、approval gate |
| 06 | `06.full-pipeline.yml` | Build → Test → Package → Deploy |
| 07 | `07.deploy-webapp.yml` | 對照組：OIDC 部署到 Azure App Service |
| 08 | `08.selfhosted-runner.yml` | Self-hosted runner（選修） |
| 09 | `09.troubleshooting.yml` | 故意失敗，用 workflow log 找錯 |

| 環境 (`APP_ENVIRONMENT`) | 橫幅顏色 |
| --- | --- |
| `test` | 藍色 |
| `production` | 紅／橘色 |
| `local`（預設，含任何無法辨識的值） | 灰色 |

## 技術規格

| 項目 | 內容 |
| --- | --- |
| Spring Boot | 4.1.1 |
| Spring Framework | 7.0.9（由 Spring Boot 管理） |
| Java | 21（本機實測 OpenJDK 21.0.11 LTS） |
| 建置工具 | Maven Wrapper（**不需要**先安裝 Maven） |
| groupId / artifactId / version | `money.gh200` / `simpleweb` / `1.0.0` |
| 主類別 | `money.gh200.simpleweb.SimpleWebApplication` |
| 建置產出 | `target/simpleweb.jar`（`<finalName>` 固定，部署腳本寫死這個路徑） |

樣式表放在 `src/main/resources/static/css/site.css`，**不使用任何 CDN**，
整個頁面不會對外連線，因此在網路受限的 VM 上也能正常顯示。

## 在本機執行

需求：只要有 **JDK 21** 就好，不需要安裝 Maven（`mvnw` 會自己下載 Maven）。

```powershell
# 啟動（預設 http://localhost:8080，環境為 local）
.\mvnw spring-boot:run
```

想模擬部署後的樣子，用 `APP_ENVIRONMENT` 切換橫幅（build SHA／時間是 build 時烤進 jar 的，不是執行期環境變數）：

```powershell
$env:APP_ENVIRONMENT = "test"
.\mvnw spring-boot:run
```

打包時可注入版本資訊（CI 就是這樣做），再直接跑 jar：

```powershell
.\mvnw -B package "-Dapp.build.sha=a1b2c3d" "-Dapp.build.time=2026-09-03T00:10:00Z"
java -jar target\simpleweb.jar
```

> Linux／macOS 或 CI runner 上請改用 `./mvnw`。

## 執行測試

```powershell
# 單元測試 + web slice 測試 + 整合測試，全部跑一遍並打包
.\mvnw verify
```

`verify` 會依序執行：

| 測試 | 類型 | 內容 |
| --- | --- | --- |
| `InfoServiceTest` | 單元測試（Surefire） | 驗證環境名稱正規化、預設值、橫幅顏色對應、主機名稱查詢失敗的容錯 |
| `HomeControllerTest` | Web slice（`@WebMvcTest`） | 驗證 `/` 回 200，且畫面上有環境名稱與 build 資訊 |
| `SimpleWebApplicationIT` | 整合測試（Failsafe，`@SpringBootTest(webEnvironment = RANDOM_PORT)`） | 真的啟動 server（**隨機 port，不會佔用 8080**）並打 `/`、`/api/info`、`/actuator/health` |

只跑單元測試：`.\mvnw test`（整合測試 `*IT` 由 Failsafe 在 `verify` 階段才執行）。

## Endpoints

| 路徑 | 方法 | 說明 |
| --- | --- | --- |
| `/` | GET | HTML 首頁，大字顯示環境、版本、build SHA、build 時間、hostname、伺服器時間、Java 版本 |
| `/api/info` | GET | 與首頁相同的資訊，JSON 格式 |
| `/actuator/health` | GET | Spring Boot Actuator health check（只開放 `health` 與 `info` 兩個 endpoint） |

`/api/info` 回應範例：

```json
{
  "application": "SimpleWeb",
  "version": "1.0.0",
  "environment": "test",
  "buildSha": "a1b2c3d4e5",
  "buildTime": "2026-09-03T00:10:00Z",
  "hostname": "MONEY-PC",
  "javaVersion": "21.0.11",
  "serverTime": "2026-09-03 00:10:13 CST"
}
```

## 環境變數與 build 資訊

執行期設定（port 與環境）走環境變數，由 systemd 的 `Environment=` 提供：

| 環境變數 | 預設值 | 說明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | HTTP 監聽的 port |
| `APP_ENVIRONMENT` | `local` | `test` / `production` / `local`，決定橫幅顏色與文字 |

對應關係寫在 `src/main/resources/application.yml`：

```yaml
app:
  environment: ${APP_ENVIRONMENT:local}
```

**build 資訊（`buildSha` / `buildTime`）不是環境變數。** 它在 build 時由 Maven resource
filtering 烤進 `src/main/resources/build-metadata.properties`（CI 傳
`-Dapp.build.sha` / `-Dapp.build.time`），再由 `BuildMetadata` 直接從 classpath 讀出。
因此設定 `APP_BUILD_SHA` / `APP_BUILD_TIME` 環境變數**無法**改寫 `/api/info` 回報的版本——
這是刻意的來源可信度設計。`app.version` 同樣在建置時由 Maven 從 `pom.xml` 的 `<version>` 填入。

因為環境是執行期讀取，**同一個 jar** 可以同時部署到 test 與 production，只靠 `APP_ENVIRONMENT` 區分；
而它回報的 build SHA 永遠是「烤進這個 jar 的那一次 build」。

## 部署到 VM

CI 建置出 `target/simpleweb.jar` 後，複製到 VM 上對應的目錄，再重啟 systemd service。
VM 上同時跑兩份，共用同一份程式碼、不同環境變數：

| Service | 環境 | Port | jar 路徑 |
| --- | --- | --- | --- |
| `simpleweb-test` | `test` | 8080 | `/opt/simpleweb/test/simpleweb.jar` |
| `simpleweb-prod` | `production` | 8081 | `/opt/simpleweb/prod/simpleweb.jar` |

Unit 檔大致長這樣（`/etc/systemd/system/simpleweb-test.service`）：

```ini
[Service]
Environment=SERVER_PORT=8080
Environment=APP_ENVIRONMENT=test
ExecStart=/usr/bin/java -jar /opt/simpleweb/test/simpleweb.jar
```

部署 workflow 只把新的 jar 複製上去並重啟服務；build SHA／時間已烤進 jar，
**不需要**寫任何 `app.env`。

部署後的驗證方式：

```bash
curl http://<vm>:8080/actuator/health   # 應為 {"status":"UP", ...}
curl http://<vm>:8080/api/info          # buildSha 應為這次 commit 的 SHA
```

如果 `buildSha` 還是上一次的值，就代表 jar 沒換成功或 service 沒重啟 —— 這是課堂上最常見的狀況。

> 實際的 workflow 與 systemd unit 檔由課程的 `.github/` 內容提供，不在本 README 範圍內。

## 用 Docker 執行（次要）

```powershell
docker build -t simpleweb:latest .
docker run --rm -p 8080:8080 -e APP_ENVIRONMENT=test simpleweb:latest
```

`Dockerfile` 是 multi-stage：build 階段用 `maven:3.9-eclipse-temurin-21`，
執行階段只留 `eclipse-temurin:21-jre` 加上一顆 jar。

## 專案結構

```
.
├── .mvn/wrapper/maven-wrapper.properties
├── mvnw / mvnw.cmd                 # Maven Wrapper（必須一起 commit）
├── pom.xml
├── Dockerfile
└── src
    ├── main
    │   ├── java/money/gh200/simpleweb
    │   │   ├── SimpleWebApplication.java
    │   │   ├── model/AppInfo.java          # 顯示用的資料模型（record）
    │   │   ├── service/
    │   │   │   ├── BuildMetadata.java       # 從 classpath 讀出烤進 jar 的 build 資訊
    │   │   │   └── InfoService.java         # 組出 AppInfo 的唯一地方
    │   │   └── web/
    │   │       ├── HomeController.java     # GET /
    │   │       └── InfoApiController.java  # GET /api/info
    │   └── resources
    │       ├── application.yml
    │       ├── build-metadata.properties    # Maven filtering 烤入 build SHA/時間
    │       ├── static/css/site.css
    │       └── templates/index.html
    └── test/java/money/gh200/simpleweb
        ├── SimpleWebApplicationIT.java
        ├── BuildMetadataFilteringTest.java
        ├── BuildProvenanceOverrideIsolationTest.java
        ├── service/BuildMetadataTest.java
        ├── service/InfoServiceTest.java
        └── web/HomeControllerTest.java
```
