# 更新日志

## v0.0.10 (2026-11-02)

- 设置页新增「API 模型选择」：支持多选本地模型，选中的模型会通过 API 服务对外暴露
- `/v1/models` 接口返回所有已选模型，外部客户端可列出并调用多个模型
- `/v1/chat/completions` 根据请求体的 `model` 字段按需切换运行时模型会话（同一时刻仅加载一个模型，节省内存）
- 内置测试页（`http://<设备IP>:8080/`）下拉框展示所有可用模型，可直接切换对话模型
- API 服务仍随对话页面退出自动关闭，不支持后台运行

## v0.0.9 (2026-11-02)

- 底部导航栏新增「设置」标签页，位于「性能测评」右侧
- 设置页内 API 设置区域新增「API 设置」与「API 控制台」入口，可直接在设置页打开对应面板
- 移除顶部菜单的「设置」入口，统一由底部「设置」标签页承载

## v0.0.8 (2026-11-02)

- 模型下载支持多文件并行下载：同一模型仓库内的多个文件通过 4 线程池并发下载，显著提升下载速度
- 下载锁从全局 `synchronized(this)` 改为按 blob 路径（内容哈希）加锁，不同文件互不阻塞，相同内容自动去重
- 进度统计改用 `AtomicLong`，线程安全地汇总所有并行文件的下载进度

## v0.0.7 (2026-11-02)

- 修复国内/国外模型下载失败：HuggingFace 下载根据设备语言环境自动选择 `huggingface.co`（海外）或 `hf-mirror.com`（国内镜像），拉取仓库信息失败时自动切换到备用主机重试
- 修复国内 App 更新下载失败：GitHub Release 下载自动使用 `ghproxy.com` 等镜像加速，国内优先镜像、海外直连，失败时自动回退

## v0.0.6 (2026-11-02)

- 修复 APK 安装签名冲突：引入仓库内固定签名 keystore（`debug.keystore`），所有构建（本地 + CI）统一使用同一签名，跨版本升级无需卸载

## v0.0.5 (2026-11-02)

- 存储管理页新增「模型存储路径」设置：支持默认（内置）、外置存储（应用专属目录）与自定义绝对路径
- 模型下载、模型扫描、内置模型拷贝、市场配置扫描统一读取配置的存储路径，用户把含 `config.json` 的模型文件夹放到该路径下即可被调用
- 修复 API 设置接口无法被浏览器正常使用的问题：服务默认绑定 `0.0.0.0`（局域网可访问）并默认开启 CORS
- 内置测试页（根路径 `/`）自动注入 API Key，浏览器直接打开即可加载模型列表并对话，无需手动拼 `?token=`
- API 设置页访问地址在绑定 `0.0.0.0` 时显示设备局域网 IP，便于浏览器直接打开

## v0.0.4 (2026-11-02)

- MnnLlmChat「关于」页更新改为检测本仓库最新 Release，自动弹出更新提示
- 用户同意后自动下载并拉起安装，下载过程显示进度，可暂停
- 下载支持断点续传：暂停或失败后保留 `.part` 文件，下次继续下载
- 安装前校验 APK 包名，版本号随仓库版本同步（App 版本 0.0.4）

## v0.0.3 (2026-11-01)

- 修复 MnnLlmChat 调试源集的 `app_name` 覆盖，桌面名称确认显示为「沫兮本地化 AI」
- 重新构建并发布两个 APK，包含访问地址预览与 MXLOGO 图标

## v0.0.2 (2026-11-01)

- 两个 App 名称统一改为「沫兮本地化 AI」
- MnnLlmChat 应用图标替换为 [MXLOGO](https://github.com/ssmhdssmhd/MXLOGO) 图标
- API 设置页新增「访问地址」实时预览，显示 `http://主机:端口/v1`，点击即可复制
- API 控制台在服务启动中也会显示完整端点地址

## v0.0.1 (2026-11-01)

- 复刻 [alibaba/MNN](https://github.com/alibaba/MNN) 源码，默认分支 `main`
- 新增 GitHub Actions 工作流 `android-build`，云端编译 APK
- MNN 原生库编译：`arm64-v8a`（含 LLM/OpenCV/OpenCL）与 `armeabi-v7a`
- 经典 Demo（`project/android/demo`）构建升级：AGP 8.7.3、Gradle 8.9、AndroidX
- MnnLlmChat（`apps/Android/MnnLlmChat`）构建接入云端工作流
- Demo 所需模型（MobileNet V2 / SqueezeNet V1.1 / Portrait）构建时自动转换
- 原生库产物包含 JNI 库 `libmnncore.so`，保证 Demo 可正常加载
- 应用图标统一取自 [MXLOGO](https://github.com/ssmhdssmhd/MXLOGO) 仓库
- 构建产物：`MNN.Demo.*.apk`、`MNN.Chat.*.apk`，自动发布到 `latest` Release
