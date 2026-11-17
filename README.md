# 相册 · WatchGallery

一款专为 **Android 11 圆屏手表**（运行完整 Android，非 Wear OS）设计的相册应用。UI 风格借鉴 GitHub 用户 vveyin 的 `f-x` 仓库（Material Design 3、圆表盘自适应、旋转表冠操作、夜间/白天双主题），相册与编辑功能对标手机相册（参考 Fossify Gallery、ImageToolbox）。

## 功能

### 相册
- 通过 MediaStore 扫描设备全部图片，按文件夹（相册）分组
- 圆屏 2 列 / 方屏 3 列网格自适应，图片安全区避让表盘边缘
- 大图查看：双指缩放、单指平移、双击复位、左右切换、旋转表冠缩放
- 权限申请（Android 13 `READ_MEDIA_IMAGES` / 低版本 `READ_EXTERNAL_STORAGE`）

### 图片编辑（对标手机相册）
- **滤镜**：原图 / 黑白 / 怀旧 / 鲜艳 / 冷色 / 暖色 / 反色
- **调节**：亮度、对比度、饱和度、色温
- **裁剪**：拖动选择区域
- **涂鸦**：多色画笔、粗细、撤销、清空
- **旋转/翻转**：90° 旋转、水平/垂直翻转、重置
- **保存**：通过 MediaStore 存入 `Pictures/WatchGallery`，无需存储权限（API 29+）

### 主题与交互
- Material 3 主题，夜间（默认黑底）/ 白天（白纸）/ 跟随系统三态切换，设置页可选
- 沉浸式全屏，表盘安全区自适应（借鉴 f-x 的 WatchMetrics 弧宽/短边缩放思路），所有页面内容约束在内切圆内
- 旋转表冠 / 滚轮 / 方向键 / 音量键多路上报

### 设置（v0.1 新增）
- **表冠适配**（完整借鉴 f-x 的两种模式）：
  - `OPPO`：走普通 MotionEvent 滚轮轴，不过滤 action/source（OPPO 系表冠 source 位不可靠）
  - `华为 / 三星`：走 `ACTION_SCROLL` + `SOURCE_ROTARY_ENCODER`，原始值 ×15 拉齐手感
  - **灵敏度**：0.1x ~ 3.0x，乘在原始滚动量上，改完即时生效并持久化
- 主题设置：夜间 / 白天 / 跟随系统

### 检查更新（v0.1 新增）
- 通过 GitHub Releases API 检查新版本（仓库地址由 BuildConfig 注入）
- 发现新版本进入下载页：显示版本信息、更新说明、**下载进度条**
- 下载完成显示**安装按钮**；APK 保存在稳定路径，安装失败返回后可**再次点击安装**，不会丢失安装包
- 需系统允许「安装未知应用」权限

## 技术栈
- Kotlin + Jetpack Compose + Material 3
- AGP 8.5.2 / Gradle 8.7 / compileSdk & targetSdk 34 / minSdk 24
- Coil 图片加载、MediaStore 存储、ColorMatrix 色彩处理

## 构建

```bash
# 需 Android SDK platform 34 + build-tools 34.0.0 + JDK 17+
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名使用项目根目录 `watchgallery.jks`（别名/口令见 `app/build.gradle.kts`，仅供测试，正式发布请更换）。

## 目录结构

```
app/src/main/java/com/watchgallery/app/
├── MainActivity.kt             沉浸式全屏 + 表冠事件分发 + 导航
├── theme/                      Material 3 明暗主题、配色、字体
├── ui/
│   ├── WatchMetrics.kt         圆屏安全区/弧宽/短边缩放
│   ├── GalleryScreen.kt        相册网格 + 权限 + 分组
│   ├── PhotoViewerScreen.kt    大图查看 + 缩放平移
│   ├── EditScreen.kt           编辑工具面板
│   └── GalleryViewModel.kt     数据/编辑/主题状态
├── data/MediaRepository.kt     MediaStore 扫描
├── edit/ImageEditor.kt         Bitmap 编辑引擎
└── util/ImageSaver.kt          MediaStore 保存
```
