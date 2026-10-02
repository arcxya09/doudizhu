# 闲来斗地主 — Kotlin 原生 Android 版

v2.0.0 将旧 WebView 实现完整替换为 Kotlin 原生应用。无 HTML/CSS/JavaScript，无浏览器内核，无联网权限，无账号、广告或内购。

[下载安装包](https://github.com/arcxya09/doudizhu/releases/tag/v2.0.0) · Android 8.0 及以上。

## 实现

- `Rules.kt`：纯 Kotlin 规则、状态机和三个难度的人机；人机只接收己方手牌和公开信息，不读取对手暗牌。
- `MainActivity.kt`：原生横屏沉浸式牌桌、系统按钮、设置、提示、战绩与 AtomicFile 存档。
- `CardViews.kt`：原生 View 绘制本地牌面贴图；ViewGroup 实际测量两排手牌，保持牌面比例与独立点击区域。
- `AudioEngine.kt`：在本机生成原创 PCM WAV 音乐和音效，通过 MediaPlayer / SoundPool 播放，处理后台暂停与音频焦点。
- `assets/cards.webp`：55 格牌面图集（54 张牌及牌背），附第三方素材许可。

默认电脑出牌间隔 1.8 秒，可设 1 秒或 2.8 秒；玩家不限时。音乐、音效可分别关闭，音量独立可调。选牌后点击出牌，非法组合或不能压过上家时禁用出牌按钮。

## 规则约定

三人叫分地主；3 至 A、2、小王、大王递增。支持单张、对子、三张、三带一/二、顺子、连对、飞机、飞机带单/对、四带二/两对、炸弹和王炸。顺子至少 5 张，连对至少 3 对，飞机至少两组三张，主体不含 2 或王。飞机单翅允许带对子但不能带双王，不能与主体同点数；对翅必须为不同点数的对子。四带二允许带一对，不允许带双王；四带两对须为两个不同点数的对子。

炸弹、王炸、春天及反春天翻倍；地主得失底分×倍数×2，农民为底分×倍数。战绩不涉及现实财物。

## 构建

JDK 17、Gradle 8.9、Android SDK 35、Kotlin 2.0.21：

```sh
gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

可用 Android Studio 打开根目录。SDK 路径设在 `local.properties` 或 `ANDROID_HOME`。本仓库不提交 Gradle wrapper 二进制，安装 Gradle 8.9 后可执行 `gradle wrapper --gradle-version 8.9`。构建依赖首次需要下载，玩家运行完全离线。

## 验证

Kotlin 单元测试覆盖全部牌型、穷举合法出牌对照、连续不出后的轮转、春天计分、农民配合及 180 局完整对局。CI 在 Android 模拟器上检查两种屏幕尺寸、横屏、无 WebView、20 张手牌可见且不重叠、点击区域、按钮可见、提示出牌和重启恢复；截图附在 Release。实体手机仍需试用验收。

原生版采用独立存档，不迁移旧 WebView 数据。测试安装包使用 debug 签名，不同 CI 构建可能签名不同，覆盖安装冲突时需要卸载旧版，原有本机数据会删除。正式长期分发应配置固定私有签名密钥。

## 牌面素材

来自 [hayeah/playing-cards-assets](https://github.com/hayeah/playing-cards-assets)，其 README 将牌面来源注明为 public-domain vector-playing-cards。保留仓库 MIT 许可，见 `app/src/main/assets/CARD_ART_LICENSE.txt`。图集按引擎编号排列，每格 160×232 像素，9 列；0—51 为 3 到 2，花色依次黑桃/红桃/梅花/方块，52 小王、53 大王、54 牌背。放大牌角由原生 Canvas 绘制。
