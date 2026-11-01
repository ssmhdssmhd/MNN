# 更新日志

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
