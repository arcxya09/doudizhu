# 应用内更新

从 v3.8.0 开始支持 GitHub Release 更新。旧版尚无更新入口，需要先手动安装一次 v3.8.0；v3.6.1 之后同签名的正式版本可直接覆盖安装。

## 使用

- 默认开启自动检查：启动或回到应用时，每 24 小时最多检查一次。失败后同样限频，可随时手动重试。
- 发现新版时提示一次，并在设置入口显示“新”。不弹窗打断牌局，不自动下载。
- 打开“设置 → 应用更新”，查看版本、更新说明和安装包大小，再点击“下载更新”。下载有进度，可取消。
- 安装前校验包名、版本、Android 兼容性、完整性及正式签名。点击“安装更新”后，按系统提示授权和确认；取消后仍可继续游戏。
- 自动检查可关闭；关闭后仍保留手动检查。断网、GitHub 不可用或请求限流均不影响离线对局。

只访问本项目的 GitHub Release 和 GitHub 的 HTTPS 文件服务，不上传手牌、战绩、存档或音乐。需要 `INTERNET` 和 `REQUEST_INSTALL_PACKAGES` 权限；不需要存储权限、账户或常驻后台服务。下载放在私有缓存目录，完整包可复用；应用关闭或进程被系统回收后不承诺断点续传。

## 实现与维护

`UpdatePolicy` 使用严格三段版本号比较，读取固定仓库最新稳定 Release，排除草稿与预发布。发布资产必须是 `doudizhu-VERSION-native.apk`，Release 正文必须包含正式签名标记及 APK SHA256；资产 digest 存在时必须与正文一致。现有正式发布脚本会自动添加这两个标记，无需额外维护更新清单。

`UpdateRepository` 限制 HTTPS 来源、跳转次数、连接/读取时间、元数据和 APK 大小。下载完成后校验大小与 SHA256，并原子改名，部分文件不能用于安装。

`UpdateInstaller` 再次检查实际 APK：包名相同、版本代码严格递增、版本名一致、系统版本支持、非调试版、固定正式证书且与当前已安装签名相同。`UpdateApkProvider` 仅以临时只读 URI 共享更新目录内的 APK，不共享存档或任意路径。

`UpdateController` 使用独立后台线程与主线程回调，不复用会被牌桌调度清空的 Handler。独立 `updates` 偏好保存开关、检查时间和已发现版本，不修改原设置或存档格式。暂停后仅更新状态；销毁、关闭自动检查或取消下载会使迟到回调失效。下载完成不会自动唤起安装器。

Android 的安装来源授权与系统安装界面由系统管理，应用不执行静默安装。参见 [Android 安装来源权限](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())、[文件临时读取授权](https://developer.android.com/training/secure-file-sharing/share-file) 和 [GitHub Release API](https://docs.github.com/en/rest/releases/releases)。

## 验证边界

自动化测试使用可控的发布响应与下载流，覆盖版本排序、摘要/大小、恶意跳转、取消与迟到回调、离线、缓存、只读分享及不兼容 APK。设备测试不依赖实时 GitHub 服务，也不擅自安装模拟更新。正式发布流程另外验证实际旧正式版到新正式版的覆盖安装及本地数据保留；不同 Android 厂商的安装确认界面仍可能不同。
