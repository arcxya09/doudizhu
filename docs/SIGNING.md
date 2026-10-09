# 正式版签名与覆盖升级

首个正式版为 **v3.6.0（versionCode 19）**，当前版本为 **v3.8.0（versionCode 26）**。所有正式版保持包名 `com.arcxya.doudizhu`，使用同一份私人签名密钥。每次发布必须递增 `versionCode`，才能通过 Android 的正常覆盖升级检查。

正式证书的 SHA-256 指纹固定为：

```
22764e23837be56b301b9c52d4738695d147a254e404a0bfdc3f2d299e5aa31a
```

公开仓库只保存 `signing/certificate.sha256` 中的证书指纹。不要修改这个指纹来绕过检查。密钥或密码丢失后，普通直接安装的 APK 无法再用原签名更新，只能换用新密钥并让用户卸载重装一次。

**v3.6.1 换用了新密钥。** 原私人备份不可用，旧指纹 `59ed2222d17c6085fe3699d80ca7434f8350882064a8732ed8f5ec232d7d86a3` 作废，v3.6.0 的安装包无法再被覆盖升级，已装 v3.6.0 的手机必须先卸载（会清除牌局、战绩、设置和导入的音乐）。当前系列从新指纹开始，此后必须一直沿用同一份备份，丢失即无法再更新。

## 首次切换说明

此前测试版使用 CI 的 debug 签名。若手机已安装旧测试版，第一次安装固定正式签名的 APK 时可能出现签名不一致。这种情况需要先卸载旧测试版，卸载会清除旧牌局、战绩、设置与导入的音乐。旧测试版私钥不可用时，无法通过改变版本号消除这个限制。

从首个正式版开始，之后的正式版继续使用本证书，可直接覆盖安装，保留应用数据。发布验收会实际安装旧正式包，再执行 `adb install -r` 安装候选包，不能用卸载重装冒充升级成功。

## 两种固定签名发布方式

项目支持两条发布路径，使用同一份私人密钥和同一张正式证书。推荐配置 GitHub Actions Secrets 后由 CI 签名；没有配置 Secrets 时，CI 只准备未签名 APK，由维护者在本地签名，再交回 CI 完成验收和发布。这条备用路径无需登录 GitHub 网页，也不向 GitHub 提交私钥。

两条路径都必须通过证书指纹、非调试属性、版本递增和真实覆盖安装检查。未签名 APK 只属于构建中间文件，不能安装为正式版，也不能上传到正式 Release。

## 使用 GitHub Actions Secrets

在仓库 **Settings → Secrets and variables → Actions → Repository secrets** 中配置四项秘密：

| Secret | 内容 |
| --- | --- |
| `DDZ_KEYSTORE_BASE64` | 私人备份中的 `keystore-base64.txt` 内容，PKCS12 密钥库的 Base64 |
| `DDZ_STORE_PASSWORD` | `store-password.txt` 中的密码 |
| `DDZ_KEY_ALIAS` | `key-alias.txt` 中的别名，当前为 `doudizhu-release` |
| `DDZ_KEY_PASSWORD` | `key-password.txt` 中的密码 |

`DDZ_KEYSTORE_PATH` 由准备脚本生成，指向 runner 临时目录中的密钥库，无需配置为 Secret。准备步骤需要四项 Secrets；签名构建步骤只需要密码和别名。它们不放在整项 job 的环境中，也不传给 SDK、Gradle 安装或模拟器步骤。签名完成后立即删除临时密钥库，job 结束时还有一次清理。

私人备份包含密钥库和密码，应保存在私密位置。不要上传到仓库、Issue、Release、Actions artifact 或公开网盘，不要把密码粘贴到日志。GitHub Secrets 的值保存后不能从设置页再次读出，私人备份必须保留。

## 不使用 Secrets 的本地签名方式

1. 将源码同步到 `main`。原生 QA 完成后，CI 用显式参数 `-PddzPrepareUnsignedRelease=true` 准备未签名候选 APK、升级基线及签名工具。普通 Release 或聚合构建仍要求固定签名凭据；这个参数只允许准备中间文件。
2. 下载该源码提交对应的准备 artifact。使用原私人 PKCS12 密钥在本地运行 `apksigner`，密码通过环境变量及工具的 `env:` 参数传入，不能放在命令参数、终端输出或日志中。只有首次正式版的基线需要使用同源码和较低 `versionCode` 构建并签名；之后的升级基线采用实际上一正式版 APK。
3. 将签名后的公开 APK 和清单提交到隔离分支 `release-artifacts/v3.6.0`。提交只新增 `release-inputs/candidate.apk`、`release-inputs/baseline.apk` 和 `release-inputs/manifest.json`，直接父提交必须是已通过 QA 的源码提交。私钥、密码、密钥备份和临时签名目录全部留在本地。
4. 独立发布任务运行 `load-offline-release.py`。脚本检查父提交、修改范围、源码的成功原生 QA、APK 哈希及版本、正式证书和非调试属性，并下载上一正式版核对升级基线必须是该 APK 的原文件。没有上一正式版时，基线版本号必须恰好比候选版小 1。随后执行同样的模拟器覆盖安装验收；全部通过才发布正式 Release。

清单只包含公开信息：

| 字段 | 内容 |
| --- | --- |
| `source_sha` | 已通过原生 QA 的 `main` 源码提交，完整 40 位 SHA |
| `version_name`、`version_code` | 源码中的版本名和递增整数版本号 |
| `package_name` | `com.arcxya.doudizhu` |
| `certificate_sha256` | 固定正式证书的公开 SHA-256 指纹 |
| `candidate_sha256`、`baseline_sha256` | 两份签名 APK 的 SHA-256 |

正式 Release 可包含候选 APK、覆盖安装验收结果及上述公开指纹。两条路径都不允许公开 PKCS12、密码或签名备份，也不允许重新生成密钥来替代丢失的原密钥。

## 发布检查

正式发布流程先完成原生调试构建、单元测试、设备界面测试与 lint，然后进行以下检查：

1. Secrets 路径由 `prepare-release-signing.py` 恢复密钥库，并用证书指纹确认是原密钥。缺少凭据时转入未签名准备路径；证书不符立即失败。
2. Gradle 生成 release APK，禁用 `debuggable`。普通 Release 任务缺少签名凭据时拒绝构建；显式准备参数生成的未签名中间 APK 必须先完成本地固定签名和独立发布验收，不能回退到 debug 签名。
3. `verify-release-apk.py` 检查 APK 签名、包名、版本号和非调试属性。
4. `check-release-upgrade.sh` 在临时 AOSP 模拟器上执行覆盖安装，确认包 UID、私有目录所有者与全部持久文件在安装后不变。首次恢复保存后，通过独立 `UpgradeSnapshotCheck.java` 严格比较所有牌局字段与战绩，允许兼容地增加存档字段，并检查战绩镜像一致；设置、标记、音乐索引和音频文件仍逐字节一致。另检查运行进程、崩溃日志和截图。
5. 全部成功后创建正式 GitHub Release；不能标记为 prerelease。已发布的同名版本不得替换 APK，后续修改须升版本。隔离分支发布时，Release 指向包含公开 APK 的提交，其直接父提交就是通过 QA 的源码。

升级验收结果位于 `release-results/`：`upgrade-passed.txt`、`release-upgrade.txt`、`release-upgrade.png` 和启动、崩溃日志。签名密钥与密码不进入这些结果。

换用新密钥后的首次正式发布没有旧正式包可下载，流程会用同一源码、同一证书构建 versionCode 19 的基线包，再覆盖安装 versionCode 20。这个检查证明本次固定证书系列的首次升级机制有效，不能证明旧密钥的 v3.6.0 可覆盖升级——那一版必须卸载重装。以后的版本使用 GitHub 上此前正式发布的 APK 作为基线；正式 Release 说明中的 `DDZ-OFFICIAL-SIGNER-SHA256` 标记必须匹配本指纹。

验收脚本按基线实际写入的存档文件（`native-table-v2` 或 `native-table-v3`）计算安装前后哈希。恢复运行后，v2 仍只读；v3 可以兼容增加字段，但必须通过完整牌局与战绩内容对照，不能用重开一局或仅比较阶段代替保存成功。

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
