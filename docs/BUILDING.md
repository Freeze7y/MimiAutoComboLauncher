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

测试编译生产 Controller / Receiver / Service，使用记录调用的 Android API 测试替身。当前 64 项断言通过。增量模块另有 `python tests/delta-regression.py`（15 个场景）及 `python tests/update-download-regression.py`（14 项下载、回退与身份检查，使用 HTTPS/Android 替身）。

没有把主机断言或构建成功当作真机兼容性、动画流畅度或 HyperOS 权限流程验证。

## 增量发布（1.4.0 起）

运行 `scripts/make-update.py --target dist/MimiAutoComboLauncher-1.4.0.apk --version 1.4.0 --code 12 --base dist/MimiAutoComboLauncher-1.3.0.apk` 可生成 `update.json` 与 MMD1 补丁。补丁基于旧版**已签名原始 APK 的 SHA256**匹配；发布前必须保留历史安装包，签名私钥不能上传。

MMD1 是 gzip 包装的有界 copy/literal 数据：魔数、目标长度、旧包与新包 SHA256、复制或新增指令。Java 按流合成，最大目标 64 MiB；失败删除输出，下载层自动回退完整包。最终 APK 与正式签名包完全一致，再由系统安装器验证签名。

`python tests/delta-regression.py` 检验跨语言合成、损坏、错误基包和边界。`scripts/publish-release.ps1 -Python <python路径> -Gh <gh路径>` 构建、为 dist 中保留的历史米米 APK 生成补丁，先上传草稿的全部附件，再公开 Release。运行前应提交并推送源码，写好 `dist/release-notes-版本.md`。不要仅上传 APK，必须同时上传 update.json、清单所列补丁与校验文件。

首次安装 1.4.0 仍用完整包；1.3.0 不含补丁客户端。后续从 1.4.0 升级时需生成以其签名 APK 为基包的补丁。未覆盖的历史版本自动用完整下载。安装授权、安装确认和更新后重新打开的具体表现需要真机验证。安装结果用可变且显式的 PackageInstaller PendingIntent 回调，校验 session 与随机 token；更新后仅针对本次期望版本尝试重新打开，并提供通知备用入口。

安装流程参考：[PackageInstaller.Session](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session) 与 [后台界面启动限制](https://developer.android.com/guide/components/activities/secure-bal)。安装确认不绕过系统；后台重开仅为尽力尝试。
