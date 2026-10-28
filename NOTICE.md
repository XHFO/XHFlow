# 第三方说明

XHFlow 自有代码和原创图标采用仓库根目录的 [MIT License](LICENSE)。以下第三方内容保留各自的版权声明及许可。

## QR Code generator library — Project Nayuki

- 上游：<https://github.com/nayuki/QR-Code-generator>
- 固定提交：`3c6d0b3cefb4e049dc337e82237c9644399716a8`
- 许可：MIT；Copyright (c) Project Nayuki。
- 随仓库分发：`app/src/io/nayuki/qrcodegen/` 下的 `QrCode.java`、`QrSegment.java`、`BitBuffer.java`、`DataTooLongException.java`。
- 四个源文件均保留上游完整 MIT 许可头。来源与 SHA256 清单见 `app/third-party/QR-Code-generator-SOURCE.txt`，上游说明见 `app/third-party/QR-Code-generator-Readme.markdown`。
- APK 保留 `app/assets/THIRD_PARTY_NOTICES.txt` 中的完整许可。此依赖用于在电视本机生成配置二维码。

## JSON-java — org.json:json:20240303

- 上游：<https://github.com/stleary/JSON-java>
- 官方 Maven 包：<https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar>
- SHA256：`3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed`
- 该版本随 JAR 的 Maven POM 许可项声明 **Public Domain**；POM 指向的许可页为 <https://github.com/stleary/JSON-java/blob/master/LICENSE>。
- 仅用于主机端 Java 测试。仓库不附带此 JAR 或其源码，构建 APK 也不打包它；Android 运行时使用系统提供的 `org.json`。测试脚本下载到忽略的 `build/deps/` 并先校验哈希，也允许显式指定同版本本地 JAR。

## 构建工具与网页资源

Android SDK、JDK、Python、Node.js 由使用者自行安装，未随仓库分发；各工具遵循各自的许可。Android SDK 安装与许可说明见 <https://developer.android.com/tools>。

网页使用系统字体，无外部字体、UI 框架或运行时 CDN 依赖。`web-console/mark.svg` 与原生对应图标是本项目原创资源。
