# 更新日志

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
