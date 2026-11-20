# 皮果刷剧 (Piguo Drama)

原生 Android（Kotlin + Jetpack Compose + Media3 ExoPlayer）短剧客户端，适配竖屏沉浸刷剧与全集连播，内置基于全剧分集点赞与互动数据的 **S / A / B / C 五维口碑评级** 与多项沉浸观剧体验增强。

## 核心特性

- **S / A / B / C 五维口碑评级**
  - 基于 **集均点赞（32%）**、**累计收藏追剧（28%）**、**正片中后段留存率（25%）**、**互动密度（10%）** 与 **近期活跃度（5%）** 计算 0–100 综合分。
  - 支持**防烂尾熔断**（尾段留存跌破阈值封顶不进 S 级）与**黑马潜力剧**识别。
  - 评分徽章与核心数据指标独立成行置顶于剧名上方，点击即可展开全剧五维拆解与**逐集点赞走势图**。
- **质量筛选与选集增强**
  - 首页推荐流顶部支持 `全部` / `去C级` / `仅A/S` 一键质量筛选。
  - 选集列表自动标注「高能集」峰值并灰化区分已看集数。
- **沉浸式播放体验**
  - Media3 ExoPlayer 边播边缓存、下集预加载、多清晰度与倍速切换。
  - 支持竖屏填充 / 完整比例切换、全屏防误触锁屏、Android 画中画（PiP）小窗、定时关闭及后台 / 息屏继续听剧。
- **账号与云端同步**
  - 支持登录账号同步云端追剧列表与观看历史进度。

## 构建与测试

### 环境要求
- JDK 17
- Android SDK（`compileSdk = 36`、`minSdk = 26`、`targetSdk = 35`）
- 在仓库根目录创建 `local.properties` 并配置本机 SDK 路径，例如：
  ```properties
  sdk.dir=D\:\\Android\\Sdk
  ```

### 常用命令

```powershell
# 运行本地协议与质量评分单元测试
.\gradlew.bat :app:testDebugUnitTest --no-daemon

# 构建 Debug APK
.\gradlew.bat :app:assembleDebug --no-daemon

# 构建 Release APK（已启用 R8 混淆与资源压缩）
.\gradlew.bat :app:assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease --no-daemon
```

产物输出路径：`app/build/outputs/apk/release/app-release.apk`。

## 作者与致谢

- **作者**：皮皮未来 · 周一 · xscanzm
- **功臣**：Gemini

## 开源许可与声明

- 本项目基于 GNU General Public License v3.0（`GPL-3.0-only`）开源，许可证全文见 [LICENSE](LICENSE)，上游协议适配与源码修改声明见 [NOTICE](NOTICE)。
- 本项目仅用于个人学习与移动端技术研究，所有影视内容与相关权益归原平台及版权方所有。
