# 闲来斗地主 · Android

完全离线的单机斗地主，中文界面，人类玩家对阵两个人机。无广告、无账号、无内购、无服务器。

## 安装

Android 8.0 及以上，建议系统 WebView 100 及以上。优先从 [Releases](https://github.com/arcxya09/doudizhu/releases) 下载 `doudizhu-1.0.0.apk` 直接安装。也可到 [Actions](https://github.com/arcxya09/doudizhu/actions/workflows/android.yml) 打开最近一次成功运行，在 Artifacts 下载 `doudizhu-1.0.0-debug-apk`，解压后安装 `app-debug.apk`。这是开发测试签名安装包；首次安装需允许来源应用安装 APK。GitHub 下载构建产物可能需要登录。

应用运行不需要网络。Android Manifest 不申请任何权限；WebView 禁止网络加载，只通过本地资源拦截器读取随 APK 打包的 HTML、CSS 和 JavaScript，不含远程字体、分析 SDK 或联网服务。

## 功能

- 54 张牌，三人叫分抢地主；全不叫重发，地主拿三张底牌先出。
- 全部常见牌型、炸弹、王炸、连续两次不出后的自由出牌、春天及反春天。
- 简单：随机合法出牌，适合练手。
- 普通：按剩余手牌结构选择出牌，保留大牌和炸弹，为农民队友让牌。
- 困难：增加拆炸弹代价、对手少牌时的拦截以及队友接牌考虑。属于启发式 AI，不是专业竞技级搜索引擎。三档均只使用自己的手牌、公开出牌与各家剩余数量，不偷看暗牌。
- 点选手牌、提示、重选、重新开局；自动保存当前牌局和本机战绩。
- 自适应横竖屏；切换到后台暂停电脑出牌。

## 规则约定

3 < 4 < … < A < 2 < 小王 < 大王。顺子至少五张，连对至少三对，飞机至少两组三张；主体均不含 2 或王。同类牌型比较必须张数相同。炸弹压普通牌，王炸最大。

飞机单翅允许一对拆作两张，但不允许双王作翅膀，翅膀不能与主体同点数；飞机对翅为不同点数的对子。四带二允许带一对，不允许带双王；四带两对必须为两个不同点数的对子。本项目采用这些明确约定，部分地方规则可能不同。

炸弹/王炸和春天/反春天分别翻倍。地主得失底分 × 倍数 × 2，每位农民为底分 × 倍数。农民共同胜负。重新开局不计战绩。数据只留本机，清除应用数据会清空牌局和战绩。

## 构建和开发

依赖：JDK 17、Gradle 8.9、Android SDK 35。首次开发构建需要下载工具依赖，玩家安装后全程离线。

```sh
node --test tests/*.test.js
gradle :app:assembleDebug :app:lintDebug
```

也可用 Android Studio 打开根目录，选择 Gradle 8.9。SDK 路径通过 `ANDROID_HOME` 或本机 `local.properties` 指定。仓库没有提交 Gradle wrapper 二进制，可安装 Gradle 8.9 后运行 `gradle wrapper --gradle-version 8.9` 生成。

产物：`app/build/outputs/apk/debug/app-debug.apk`。自动构建使用 GitHub Actions，无须把密钥提交到仓库。不同 CI 构建的临时 debug 签名可能不同；跨构建安装如提示签名不一致，需要先卸载旧测试版（会清除本机数据）。正式长期分发应配置固定私有签名密钥。

核心目录：

- `app/src/main/assets/engine.js`：纯规则、发牌、状态机、人机策略，可直接用 Node 测试。
- `app/src/main/assets/app.js`：界面、存档、轮转、计分。
- `app/src/main/assets/style.css`：本地自适应牌桌。
- `app/src/main/java/com/arcxya/doudizhu/MainActivity.java`：Android 生命周期及仅本地 WebView 容器。
- `tests/engine.test.js`：牌型、比较、穷举出牌对照、轮转、计分及 180 场固定种子完整对局。

## 验证范围

已通过规则及 AI 自动测试，以及 Chromium 手机尺寸下的选牌、提示出牌、续局、横屏操作区、难度设置与结算去重检查。CI 会编译 APK 并运行 Android lint。尚未完成 Android 真机体验验收，建议安装后检查：飞行模式连续对局、切后台恢复、杀进程恢复、旋转屏幕、叫分及全部牌型、提示与手动出牌。

界面冒烟测试（开发环境，可选）：

```sh
npm install --no-save playwright
npx playwright install chromium
node tests/ui-smoke.cjs
```

已有 Chromium 可设置 `CHROMIUM_PATH` 指向可执行文件。此依赖仅用于开发测试，不会打包进 APK。
