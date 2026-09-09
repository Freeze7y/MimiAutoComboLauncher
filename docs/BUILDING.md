# 构建与签名

## 已验证的直接 SDK 构建

要求 PowerShell 7、JDK 17（java/javac/jar/keytool 在 PATH）。从 Google 官方下载并解压：

- https://dl.google.com/android/repository/platform-36_r02.zip → `tools/sdk-platform/android-36/android.jar`
- https://dl.google.com/android/repository/build-tools_r36_windows.zip → `tools/sdk-build-tools/android-16/`

在仓库根目录执行：

```powershell
./scripts/build-apk.ps1
```

流程：aapt2 → javac → R8 → zipalign → apksigner。输出在 `dist/`。

首次构建会在 `signing/` 生成本地签名密钥和随机密码；它们不在源码仓库中。自己生成的签名不能覆盖安装官方 Release 的同包名 APK。维护者后续发布沿用已有私钥，仓库不提供该私钥。

版本号需要同步修改 `app/build.gradle`、`scripts/build-apk.ps1` 与应用诊断文字。

## Android Studio / Gradle

提供 AGP 8.13.2 工程，可用 Gradle 8.13、JDK 17 导入。该路径尚未作为发布验证流水线执行；Gradle release 默认未配置签名。实际发布使用上面的直接 SDK 脚本。

## 主机回归测试

要求 Python 3 与 JDK：

```powershell
python tests/host-regression.py
```

测试编译生产 Controller / Receiver / Service，使用记录调用的 Android API 测试替身。当前 39 项断言通过。

没有把主机断言或构建成功当作真机兼容性、动画流畅度或 HyperOS 权限流程验证。
