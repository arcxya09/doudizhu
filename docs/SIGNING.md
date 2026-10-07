# 正式版签名与覆盖升级

首个正式版计划为 **v3.6.0（versionCode 19）**。所有正式版保持包名 `com.arcxya.doudizhu`，使用同一份私人签名密钥。每次发布必须递增 `versionCode`，才能通过 Android 的正常覆盖升级检查。

正式证书的 SHA-256 指纹固定为：

```
59ed2222d17c6085fe3699d80ca7434f8350882064a8732ed8f5ec232d7d86a3
```

公开仓库只保存 `signing/certificate.sha256` 中的证书指纹。不要修改这个指纹来绕过检查，也不要重新生成密钥。密钥或密码丢失后，普通直接安装的 APK 无法再用原签名更新。

## 首次切换说明

此前测试版使用 CI 的 debug 签名。若手机已安装旧测试版，第一次安装固定正式签名的 APK 时可能出现签名不一致。这种情况需要先卸载旧测试版，卸载会清除旧牌局、战绩、设置与导入的音乐。旧测试版私钥不可用时，无法通过改变版本号消除这个限制。

从首个正式版开始，之后的正式版继续使用本证书，可直接覆盖安装，保留应用数据。发布验收会实际安装旧正式包，再执行 `adb install -r` 安装候选包，不能用卸载重装冒充升级成功。

## 配置 GitHub Actions

在仓库 **Settings → Secrets and variables → Actions → Repository secrets** 中配置四项秘密：

| Secret | 内容 |
| --- | --- |
| `DDZ_KEYSTORE_BASE64` | 私人备份中的 `keystore-base64.txt` 内容，PKCS12 密钥库的 Base64 |
| `DDZ_STORE_PASSWORD` | `store-password.txt` 中的密码 |
| `DDZ_KEY_ALIAS` | `key-alias.txt` 中的别名，当前为 `doudizhu-release` |
| `DDZ_KEY_PASSWORD` | `key-password.txt` 中的密码 |

`DDZ_KEYSTORE_PATH` 由准备脚本生成，指向 runner 临时目录中的密钥库，无需配置为 Secret。准备步骤需要四项 Secrets；签名构建步骤只需要密码和别名。它们不放在整项 job 的环境中，也不传给 SDK、Gradle 安装或模拟器步骤。签名完成后立即删除临时密钥库，job 结束时还有一次清理。

私人备份包含密钥库和密码，应保存在私密位置。不要上传到仓库、Issue、Release、Actions artifact 或公开网盘，不要把密码粘贴到日志。GitHub Secrets 的值保存后不能从设置页再次读出，私人备份必须保留。

## 发布检查

正式发布流程先完成原生调试构建、单元测试、设备界面测试与 lint，然后进行以下检查：

1. `prepare-release-signing.py` 从 Secrets 恢复密钥库，并用证书指纹确认是原密钥。缺少凭据或指纹不符立即失败。
2. Gradle 生成真正的 release APK，禁用 `debuggable`。发布任务缺少签名凭据时拒绝构建，不能回退到 debug 签名。
3. `verify-release-apk.py` 检查 APK 签名、包名、版本号和非调试属性。
4. `check-release-upgrade.sh` 在临时 AOSP 模拟器上执行覆盖安装，确认包 UID、私有目录所有者、牌局与战绩、设置、导入音乐索引和音频文件保持不变。恢复后再次核对全部文件哈希，并检查运行进程、崩溃日志和截图。
5. 全部成功后创建正式 GitHub Release；不能标记为 prerelease。已发布的同名版本不得替换 APK，后续修改须升版本。

升级验收结果位于 `release-results/`：`upgrade-passed.txt`、`release-upgrade.txt`、`release-upgrade.png` 和启动、崩溃日志。签名密钥与密码不进入这些结果。

第一次正式发布还没有旧正式包可下载，流程会用同一源码、同一证书构建 versionCode 18 的基线包，再覆盖安装 versionCode 19。这个检查证明本次固定证书系列的首次升级机制有效，不能证明旧 debug 测试版可覆盖升级。以后的版本使用 GitHub 上此前正式发布的 APK 作为基线；正式 Release 说明中的 `DDZ-OFFICIAL-SIGNER-SHA256` 标记必须匹配本指纹。

## 本地验收

配置本地环境变量 `DDZ_KEYSTORE_PATH`、`DDZ_STORE_PASSWORD`、`DDZ_KEY_ALIAS`、`DDZ_KEY_PASSWORD` 后，可按 CI 相同方式构建和验证。不要在共享终端输出变量值。

覆盖安装脚本要求 Android SDK Build Tools 35.0.0 与可取得 root 的 AOSP Android 35 模拟器。脚本同时检查 `emulator-*` 序列号、`ro.kernel.qemu=1` 和 root 用户，拒绝实体手机。测试只在基线安装前清理模拟器旧数据；正式候选包安装只允许 `adb install -r`，失败即停止。

脚本会准备困难难度、慢速 AI、静音与本地音乐测试数据。启动基线后，等到原生界面出现可点击的“叫地主”或“提示”，再通过 Home 保存牌局并停止应用。玩家回合不限时，这让恢复前后的牌局哈希保持稳定，避免 AI 正常出牌造成假失败。

## 官方参考

- [Android 应用签名](https://developer.android.com/studio/publish/app-signing)
- [Android 应用更新机制](https://developer.android.com/studio/publish/versioning)
- [apksigner](https://developer.android.com/tools/apksigner)
- [Android Debug Bridge](https://developer.android.com/tools/adb)
- [GitHub Actions Secrets](https://docs.github.com/en/actions/security-for-github-actions/security-guides/using-secrets-in-github-actions)
