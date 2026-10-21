# Rewindpix × OpenFilm6K 相机端胶卷同步 — 实施计划

> **For agentic workers:** 本计划是跨固件逆向 + OpenFilm6K 相机端功能的研究/实施路线图。执行前先读本文件顶部的「当前状态」和「未决问题」；每完成一个 Task 更新本文件的 Checklist。

**Goal:** 让 Rewindpix PS135 相机通过 WiFi 从 OpenFilm6K 拉取胶卷列表与胶卷数据，在相机上通过实体按键选择胶卷并直接拍摄，最终把原片和所选胶卷名交给 OpenFilm6K 后期流水线。

**Architecture:** 相机端（Novatek NA51089 RTOS）负责联网、拉取胶卷包、缓存到 SD、按键选择、把胶卷加载进相机现有 Film Mode 引擎；OpenFilm6K 主机（手机/PC）负责把节点管线烘焙成相机可用的 3D LUT/参数包，并通过 HTTP 提供清单与下载。相机端不运行 OpenFilm6K 的 GL 引擎，只消费烘焙后的胶卷数据。

**Tech Stack:** Novatek RTOS / ARM Cortex-A9 / BCL1+LZMA 固件包；OpenFilm6K Android 主机（Java + C++/GL ES3，`:8800` HTTP）；`.properties` 管线 + `.cube` LUT；相机端可能复用 RTOS 内置 libcurl（`NVT_CURL_SSL_OPENSSL`）或自写最小 HTTP 客户端。

**Spec / 上下文:**
- 固件逆向现场：PC `~/rw-re/`（官方 1.2.1D、解压 RTOS、`build_sd.py`、`camera_pull.py`）
- OpenFilm6K 主仓库：PC `~/code/openfilm6k-public/`
- 现有相机端参考实现（Sony A6000）：`camera/src/com/openfilm6k/camera/MainActivity.java`
- 主机服务：`host/src/com/openfilm6k/host/Server.java`（`/films`、`/process`、`/ingest`）
- 胶卷标定/烘焙：`~/.config/opencode/skills/iterating-film-pipelines/tools/bake_grade_into_cube.py`

---

## 当前状态（2026-10-20）

### 已完成

- 官方固件 1.2.1D 逆向：
  - Novatek NA51089，Cortex-A9，纯 RTOS（`cardv`），不是 Linux。
  - 全量包 = NVT FW2 all-in-one：FDT `0xb0`、ISP 调参 `0x41f0`、U-Boot `0x270b0`、RTOS `0x7f530`。
  - RTOS = BCL1 + LZMA（algorithm 11），解压后 9,475,304 字节，装载地址 `0x10000`。
  - 完整性：整包 + U-Boot/RTOS 分区的 16-bit NVT sum；RTOS 无 RSA 签名；U-Boot 不动即可回滚。
- 自制定制固件 `~/rw-re/FW_X94A_sd.bin`（静态验证通过，**未上机**）：
  - 拍照 UI 定时器 `UIFlowWndPhoto_OnTimer`（`0x4491f8`）挂一次性启动器，开机后自动调用 `SetupExe_OnWifiStart`（`0x41d330`）。
  - `SetupExe_OnWifiStart` 的模式读取点 `0x41d37c` 改为读 SD 配置 `A:\wifi.txt`：
    - 第 1 行 SSID，第 2 行密码；≤31/≤26 字节；2.4GHz。
    - 调 `UINet_SetSSID`/`UINet_SetPASSPHRASE`/`UINet_SetMode(1)`，并同步 UI 命令 `0x8a=1`。
    - 配置缺失/非法 → 返回 0，回退原厂 AP 模式。
  - 代码洞：`0x1238c`（原 0xFF 空洞）；FS API：`fs_open 0x363d54` / `fs_read 0x363e4c` / `fs_close 0x363dd0`。
  - 重打包保持原厂文件大小 3,304,240 字节；BCL/整包校验和均为 0；解压后 RTOS 仅 642 字节变化。
- OpenFilm6K 主机 API 已确认：
  - `GET /films` → 胶卷名列表（换行分隔，`----` 分隔自定义卷）。
  - `GET /process?img=&film=&out=` → 渲染。
  - `GET /ingest?...` → 入片。
  - A6000 相机端 `MainActivity.pullFilmList()` 拉 `/films`，C1/C2/Fn 浏览胶卷、按名字持久化选择、上传到 `/ingest`。
- Novatek 原厂 HTTP API（用于拉原片）：
  - `GET /?custom=1&cmd=3001&par=2`（播放模式）
  - `GET /?custom=1&cmd=3015` → `<LIST><ALLFile><File><NAME>/<FPATH>A:\.../<SIZE>/<TIME>`
  - `GET /<去掉 A:\ 的路径>` 下载文件
  - 客户端脚本 `~/rw-re/camera_pull.py`。

### 未验证 / 风险

- 定制固件未上机；`DELAY=50` 的 tick 频率是估计值；相机若连不上 AP 会停在 STA 模式，删 `wifi.txt` 重启可回退 AP。
- 原厂 HTTP 服务在 STA 模式下是否仍监听未验证。
- Rewindpix 的 Film Mode 胶卷格式、上传协议、按键处理、LUT 引擎能力均未逆向。
- 相机是否支持同时 AP+STA 未确认；当前方案是单模式（STA 时不发原厂 AP）。

---

## 全局约束

- 相机 WiFi 模块 RTL8189：**仅 2.4GHz**；密码 ≤26 字节（现有 `UINet_SetPASSPHRASE` 缓冲限制）。
- 相机无 Linux、无 Android；所有代码必须是 ARMv7 RTOS 可执行代码，不能直接复用 A6000 的 Java app。
- 固件升级通过 SD 卡 all-in-one 包；U-Boot 不动，坏 RTOS 可用官方固件回滚。
- 不修改与主任务无关的系统配置；不 `git push`（用户明令）。
- 胶卷数据/算法遵守 OpenFilm6K IP 红线：不使用 FIMO 解密资产；只用黑盒拟合/公开数据。
- 相机端尽量复用原厂 Film Mode（C1/C2/C3、胶片计数器、M 键），不新造一套 UI 框架。

---

## 文件结构（预计）

**OpenFilm6K 仓库（PC `~/code/openfilm6k-public/`）**
- `host/src/com/openfilm6k/host/Server.java` — 新增 `/camera/films`、`/camera/film/<name>`（或复用 `/films` + 静态文件）。
- `host/src/com/openfilm6k/host/CameraFilmExport.java`（新）— 胶卷清单、烘焙 LUT、hash、缓存。
- `camera_rewindpix/`（新，可选）— Rewindpix 相机端协议/格式文档、测试包、构建脚本。
- `docs/plans/2026-10-20-rewindpix-camera-film-sync.md` — 本计划。
- `docs/rewindpix-film-format.md` — 逆向结论（Task 2/3 产出）。

**固件工作区（PC `~/rw-re/`）**
- `FW_X94A_sd.bin` — 当前联网固件。
- `build_sd.py` — 联网/配置构建脚本。
- `rtos121d.bin` / `rtos121d_sd.bin` — 官方/定制解压 RTOS。
- `camera_pull.py` — 原片 HTTP 拉取客户端。
- `film_sync/`（新）— 相机端胶卷同步代码、测试包、注入脚本。
- `rewindpix-fw.bin` — 官方 1.2.1D 回滚镜像。

---

## Task 1: 上机验证联网固件与原片拉取

**目标:** 确认 `FW_X94A_sd.bin` 能开机、自动连 STA、拿到 IP，且原厂 HTTP API 可列文件/下载。

**步骤**
- [ ] SD 根目录放 `wifi.txt`（两行，2.4GHz WPA2，密码 ≤26 字节），`FW_X94A_sd.bin` 改名 `FW_X94A.bin`，按官方方式刷入。
- [ ] 开机等待 5–15s；路由器 DHCP 列表找新设备；记录 IP/MAC。
- [ ] 从同网段 PC/手机执行：
  - [ ] `curl "http://<IP>/?custom=1&cmd=3015"` → 是否有 `<ALLFile>`
  - [ ] `python3 ~/rw-re/camera_pull.py <IP> list`
  - [ ] `python3 ~/rw-re/camera_pull.py <IP> pull ./originals`
- [ ] 如果连不上：删除/改名 `wifi.txt` 重启，确认回退 AP；检查 SSID/密码/2.4GHz。
- [ ] 如果 HTTP 不通：记录是否 ping 通、80/8192 端口是否开放；进入 Task 1b。

**验收**
- 相机能自动连 STA 并拿到 IP。
- `cmd=3015` 返回文件列表；至少能下载一张 JPEG。
- 失败时可回退 AP / 官方固件。

**Task 1b（仅 HTTP 不通时）:** 逆 `nvtweb`/`msdcnvt` 的绑定/启动逻辑，确认 STA 模式下 HTTP 服务是否启动；必要时在 `SetupExe_OnWifiStart` 成功路径补启动 HTTP/IPC（地址待定）。

---

## Task 2: 逆向 Rewindpix 胶卷加载协议

**目标:** 搞清楚官方 App 如何把“胶卷/滤镜”加载进相机，以及相机把胶卷存在哪里、格式是什么。

**入口**
- 官方 App（Rewindpix）未装在测试手机；优先拿 APK：
  - [ ] 从用户手机/Play Store/APKPure 获取 APK。
  - [ ] PC `~/tools/jadx/bin/jadx` 反编译；搜 `film`、`lut`、`cube`、`filter`、`upload`、`wifi`、`http`、`8080`、`query.xml`。
- 或抓包：
  - [ ] 手机连相机 AP，开 mitmproxy/PCAP，操作 App 加载/切换胶卷。
  - [ ] 记录请求 URL、方法、body、文件扩展名、相机返回。
- 固件侧：
  - [ ] 搜 RTOS 字符串：`FILM_FILTER_C1/C2/C3`、`load film`、`unload`、`WIFI`、`XML`、`cube`、`lut`、`3D`、`filter`。
  - [ ] 找 Film Mode 的文件路径/存储位置（SD `A:\...` 或内部 flash）。
  - [ ] 找 App 命令处理函数（`WiFiCmd_OnExe...` / `WifiApp_SendCmd` / `XML_QueryCmd`）。
- SD 侧：
  - [ ] 用官方 App 加载一个胶卷，拔出 SD/用 USB 读卡，找新增/变化的文件；记录目录、文件名、大小、magic。
  - [ ] 对比 `.cube` 文本格式；确认是否标准 3D LUT、维度（17³/33³/64³）、是否压缩/加密。

**交付**
- `docs/rewindpix-film-format.md`：协议端点、请求/响应样例、文件格式、存储路径、槽位数量、校验方式。
- 一个可重复的最小测试：用 SD 或 App 协议加载一个自制胶卷，相机 Film Mode 能看到并应用。

**验收**
- 能在不依赖官方 App 的情况下，把一个自制胶卷文件放进相机并让 Film Mode 识别。
- 明确 LUT 维度/格式/字节序/是否支持任意 LUT。

---

## Task 3: 解码相机 Film Mode 的按键/UI

**目标:** 找到 M 键、快门、过片、WiFi 键的事件处理，确定如何加入“浏览/选择胶卷”。

**步骤**
- [ ] 从固件字符串/代码定位：
  - `OnWiFiMode`、`SetupExe_OnWifiStart`、`FILM_FILTER_C1/C2/C3`、`M button`、`winder`、`Key`、`OnKey`。
  - LCD 上的胶卷编号/计数器（`34/36`、`00/00`）由哪个函数绘制。
- [ ] 在相机上实测：
  - 短按/长按 M、WiFi、快门、过片分别触发什么。
  - Film Mode 下切 C1/C2/C3 的方式。
- [ ] 设计最小交互：
  - 方案 A（优先）：长按 M 进入胶卷浏览，快门/过片切换，M 确认；LCD 显示胶卷名/编号。
  - 方案 B：复用现有 C1/C2/C3 槽位，把 OpenFilm6K 胶卷映射到 3 个槽 + 分页。
  - 方案 C：WiFi 键 + 快门组合。
- [ ] 确认按键回调是否可安全注入/扩展，且不影响拍照主流程。

**交付**
- `docs/rewindpix-keymap.md`：按键事件、回调地址、LCD 绘制入口、选定交互方案。
- 一个最小按键注入/选择 demo（不改拍照逻辑）。

**验收**
- 能在相机上通过实体按键切换至少 2 个胶卷，LCD 有明确反馈。

---

## Task 4: OpenFilm6K 主机端胶卷导出 API

**目标:** 让主机把 OpenFilm6K 胶卷以相机可消费的形式提供：清单 + 烘焙 LUT/参数 + hash。

**步骤**
- [ ] 在 `Server.java` 增加只读端点（不改动现有 `/films` 语义）：
  - `GET /camera/films` → JSON/行文本：`name`、`version`、`lut_url`、`sha256`、`size`、可选 `params`。
  - `GET /camera/film/<name>` → 相机格式胶卷包（或 `.cube`）。
- [ ] 新增 `CameraFilmExport.java`：
  - 扫描 `/sdcard/OpenFilm6K/pipelines/*.properties` + `luts/*.cube`。
  - 对每个卷把 `lut` + `grade` 链烘焙成 64³/33³ cube（复用 `bake_grade_into_cube.py` 的算法，Java 侧实现或预生成缓存）。
  - 对 glow/grain/vig 等非 LUT 节点：记录为相机端可选参数；若相机不支持，明确标注“仅颜色近似，颗粒/辉光留给后期”。
  - 生成稳定 hash，缓存到 `files/camera_films/`，避免每次请求重烘。
- [ ] 保持 `/films` 给 A6000 app 不变；新端点独立。
- [ ] 用 `curl` 验证：
  - `curl http://<host>:8800/camera/films`
  - `curl -O http://<host>:8800/camera/film/<name>`
- [ ] 如果相机只能吃一种固定格式，把导出逻辑做成可配置（cube 维度/字节序/头部）。

**验收**
- 主机能返回胶卷清单和可下载的胶卷包；包 hash 稳定；不破坏现有 `/process`、`/ingest`、`/films`。

---

## Task 5: 相机端 HTTP 同步客户端

**目标:** 相机开机联网后从 OpenFilm6K 主机拉清单/胶卷包，缓存到 SD，校验后供 Film Mode 使用。

**步骤**
- [ ] 确认 RTOS 是否可直接调用 libcurl：
  - 固件含 `NVT_CURL_SSL_OPENSSL`、`curl` 相关字符串；找 `curl_easy_init`/`curl_easy_perform` 符号或封装。
  - 若可用：在 RTOS 里写一个小任务/函数，GET `http://<host>:8800/camera/films`。
  - 若不可用：用现有 socket/lwIP + 极简 HTTP/1.0 客户端（只处理 GET、Content-Length、200）。
- [ ] 配置主机地址：
  - 首选 SD `A:\openfilm6k.txt`：`host=192.168.x.x` / `port=8800` / `auto=1`。
  - 或复用 `wifi.txt` 第 3/4 行。
- [ ] 同步流程：
  - [ ] 拉清单 → 解析 name/version/hash/url。
  - [ ] 对缺失/版本变化的胶卷下载到 `A:\OpenFilm6K\films\<name>.*`。
  - [ ] 校验 size/sha256（若相机端实现 SHA256 成本高，可先用 size + 简单 checksum；host 端提供）。
  - [ ] 原子替换（临时文件 + rename），失败保留旧包。
  - [ ] 写 `A:\OpenFilm6K\films\index.txt`（name/version/槽位）。
- [ ] 触发时机：开机 STA 成功后；或长按 WiFi/M 键手动同步；或定时重试。
- [ ] 失败回退：无网络/主机不可达 → 保留已有胶卷，不影响拍照；不阻塞 UI。

**交付**
- `film_sync/` 下的 ARM 代码 + 构建/注入脚本（复用 `build_sd.py` 的 cave/重打包链路）。
- 一个最小同步 demo：相机从主机拉一个胶卷包并落盘。

**验收**
- 相机能列出主机上的胶卷；断网/主机离线时不影响开机和拍照。
- 下载的包 hash 与主机一致。

---

## Task 6: 相机端胶卷选择与加载

**目标:** 把同步下来的胶卷接入相机 Film Mode，用按键选择，LCD 显示当前胶卷。

**步骤**
- [ ] 按 Task 2 的格式，把胶卷包转换成相机 Film Mode 可加载的槽位（C1/C2/C3 或自定义槽）。
- [ ] 选择状态：
  - [ ] 按胶卷**名字**持久化（参考 A6000 app 的 `selfilm`），不按索引。
  - [ ] 启动时恢复上次选择；包缺失时回退第一个/内置 C1。
- [ ] 按键交互（按 Task 3 方案）：
  - [ ] 进入浏览：显示胶卷名/序号/自定义标记。
  - [ ] 切换：快门/过片/上下键。
  - [ ] 确认：M 键/快门；LCD 更新。
- [ ] 加载：调用相机现有 film load 函数（Task 2 定位），确保切换后下一张照片使用新 LUT。
- [ ] 失败处理：包损坏/格式不支持 → 提示并保留上一个可用胶卷。
- [ ] 保持原厂内置 C1/C2/C3 可选（可放在列表末尾）。

**验收**
- 相机上可浏览 ≥2 个从 OpenFilm6K 同步的胶卷，选择后拍照颜色变化符合预期。
- 重启后选择保持。

---

## Task 7: 端到端 OpenFilm6K 流水线

**目标:** 相机拍摄 → 原片 + 胶卷名进入 OpenFilm6K → 主机用同名管线处理/归档。

**步骤**
- [ ] 相机端在照片 EXIF/文件名/旁车文件里记录所选胶卷名（若原厂不支持，写入 `A:\OpenFilm6K\shots\<file>.json` 或利用现有 XML）。
- [ ] 主机端拉片：
  - [ ] 复用 `camera_pull.py` 的 Novatek HTTP API 拉原片。
  - [ ] 读取胶卷名映射；调用 `/process?img=&film=<name>&out=` 或在 OpenFilm6K 里入片。
- [ ] OpenFilm6K ingest：
  - [ ] 使用现有 `/ingest` 或新增批量接口；确保胶卷名与主机 `pipelines` 一致。
  - [ ] 处理非 LUT 节点（glow/grain/vig）在主机侧补全。
- [ ] 验证：同一张原片，相机直出（LUT）与主机完整管线（LUT+glow/grain）颜色方向一致；差异记录在文档。
- [ ] 批量/断点续传/去重（按文件名+size+hash）。

**验收**
- 相机拍一张 → 主机自动/手动拉到 → 用同名胶卷渲染 → 输出可对比。
- 胶卷名在传输链路中不丢失。

---

## Task 8: 稳定性、回滚与文档

- [ ] 相机端日志：`A:\OpenFilm6K\log.txt`（同步/选择/错误），可在 PC 读取。
- [ ] 失败回退：无 SD/无网络/主机离线/包损坏 → 保留原厂 AP + 内置胶卷。
- [ ] 升级/回滚：
  - 官方 `FW_X94A.bin` 可回滚 RTOS。
  - 定制包保持与原厂同大小/同分区布局，校验和工具化。
- [ ] 文档：
  - [ ] `docs/rewindpix-film-format.md`
  - [ ] `docs/rewindpix-keymap.md`
  - [ ] `docs/rewindpix-openfilm6k-workflow.md`（用户操作：配置 WiFi、同步胶卷、选择、拉片）
- [ ] 安全：不在日志/固件里硬编码 WiFi 密码；`wifi.txt` 只留 SD；host 地址可配置。

---

## 未决问题（执行前先回答）

1. Rewindpix 的 Film Mode 是否接受标准 `.cube`？维度/字节序/是否加密？
2. 胶卷存在 SD 还是内部 flash？有几个槽位？是否可扩展？
3. 官方 App 的胶卷上传协议是 HTTP、XML 还是自定义 TCP？能否不装 App 直接写 SD？
4. 相机按键（M/WiFi/快门/过片）哪些可安全复用？LCD 是否足以显示胶卷名？
5. RTOS 是否可直接用 libcurl？若不能，最小 HTTP 客户端成本多少？
6. RTL8189 是否支持 AP+STA 并发？若不支持，STA 模式下如何保留手机 App 的应急入口？
7. OpenFilm6K 的 glow/grain/vig 在相机端如何近似？是否接受“相机只做颜色 LUT，颗粒/辉光后期补”？
8. 相机 CPU/内存是否足以在拍摄前实时应用 3D LUT？现有 Film Mode 已证明可以，但新 LUT 维度可能影响帧率。

---

## 建议执行顺序

1. Task 1（上机验证联网/HTTP）— 先确认底座。
2. Task 2（胶卷格式/协议）— 最关键的未知。
3. Task 3（按键/UI）— 与 Task 2 并行。
4. Task 4（主机导出）— 可与 Task 2/3 并行。
5. Task 5（相机同步）— 依赖 Task 2/4。
6. Task 6（选择/加载）— 依赖 Task 2/3/5。
7. Task 7（端到端）— 依赖全部。
8. Task 8（收尾）。

---

## 当前可复用资产

- 固件重打包：`~/rw-re/build_sd.py`（BCL1+LZMA、校验和、同大小）。
- 联网/配置：`A:\wifi.txt` + 定时器 hook。
- 原片拉取：`~/rw-re/camera_pull.py`（Novatek `cmd=3015` + 直接下载）。
- OpenFilm6K 参考实现：`camera/src/com/openfilm6k/camera/MainActivity.java` 的 `pullFilmList()`、胶卷浏览、`selfilm` 持久化、`/ingest`。
- 胶卷烘焙：`~/.config/opencode/skills/iterating-film-pipelines/tools/bake_grade_into_cube.py`。
- 逆向/反编译：PC `~/.local/bin/rea` + Ghidra 12.1.4；`rtos121d.elf` 可分析 14,710 个函数。

---

## 变更记录

- 2026-10-20：初版。完成联网固件静态验证；记录胶卷同步/按键选择/端到端路线与未决问题。
