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
  - **表冠测试**：页内实时显示收到的表冠事件，便于确认哪种模式适配你的表（v0.2）
- 主题设置：夜间 / 白天 / 跟随系统

### 稳定性修复（v0.2）
- **权限检测**：每次回到前台重新探测；被拒且「不再询问」时提供「去系统设置」入口；空相册可「重新扫描」
- **读不到照片兜底**：MediaStore 缺列不再导致整表为空；额外直接扫描 `Pictures/DCIM/Downloads` 常见目录
- **表冠 OPPO 模式**：修复增量被累加成总位移导致滚到底后失效的问题，改为逐条事件流投递；旋转编码器式表冠自动增益

### 相册空白修复（v0.3）
- **布局根因**：标题栏的安全区容器 `fillMaxSize` 放在 `Column` 里会吃掉整屏高度，网格高度被压成 0，看起来就是"照片列表是空的"。现在整屏统一套安全区，标题 wrap content、网格 `weight(1f)`
- **安全区改为几何正确**：圆内最大可完整显示的区域是以圆心为中心的正方形（≈0.707×直径），不再只是加 padding 导致四角被弧边切
- **读取照片五级兜底**：images 表逐卷查询 → files 表 `media_type=IMAGE` → 主动触发系统媒体扫描（adb push 的照片不进媒体库）→ 目录直扫 → 应用内导入目录
- **手动导入**：空相册可直接用系统文件选择器导入照片，内容复制进应用私有目录，永远读得到
- **扫描诊断页**：设置 → 扫描诊断，展示权限、媒体库行数、目录命中、异常信息

### 分页加载（v0.6）
- **主页一直转圈修复**：MediaStore 已查到照片时跳过全盘目录遍历（逐个 stat 几千文件很慢）；只有媒体库为空才做目录兜底
- **分页加载**：主页先显示前 30 张，滑到底部再追加，底部提示已显示 X/Y 张；点开大图仍可翻全部 1324 张

### 显示与交互调整（v0.5）
- **照片列表改为 Wear OS 风格全屏缩放列表**：去掉圆屏矩形安全区，列表占满整屏，靠「中间大、两边小并淡出」的缩放效果避开表盘弧边（等同原生 ScalingLazyColumn 观感）
- **设置页列表接入表冠**：设置主页、表冠适配、主题、扫描诊断各页现在都能用表冠滚动（之前只能用触摸）
- 本版 versionName 0.5，versionCode 保持 5（检查更新已改为按 versionName 比较，不受影响）

### 检查更新修复（v0.4）
- 旧逻辑拿 GitHub tag 解析出的序号（v0.3 → 3）跟 APK 的 `versionCode`（v0.2 的 versionCode 是 3）比较，编号体系不同，导致装了 v0.2 时把线上的 v0.3 误判为"已是最新版本"
- 现在比较双方统一从 `versionName` 的小数部分提取序号（0.2 → 2、0.3 → 3、0.15 → 15），不再使用 `versionCode`
- "已是最新版本"页同时显示本机版本与线上版本，便于确认是否真的请求到了 GitHub

### 版本号规则
从 `0.1` 起持续递增：`0.1 → 0.2 → … → 0.15 → 0.16 → 0.17 → …`，versionCode 同步 +1，每个版本打 tag 并发布到 GitHub Releases。

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
