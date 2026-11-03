# 广州地铁（安卓 App 工程）

网页版小游戏的安卓外壳：只允许竖屏，运行时屏幕常亮，用手机系统的语音合成报站，支持导入真实报站录音。

- 网页内容：`app/src/main/assets/www/index.html`
- 安卓代码：`app/src/main/java/com/gzmetro/sim/MainActivity.java`
- 应用名称：`app/src/main/res/values/strings.xml`
- 应用图标：`app/src/main/res/drawable/ic_launcher_foreground.xml`（可在 Android Studio 里用 Image Asset 换成自己的图片）

## 用 GitHub 自动生成 APK

1. 在 github.com 新建一个仓库（选 Private）。
2. 点 “uploading an existing file”，把本文件夹里的**全部内容**（包括 `.github` 文件夹）拖进去，点 Commit changes。
3. 打开 Actions，等 “打包安卓 APK” 跑完（绿色 ✓，约 5～10 分钟）。
4. 在这次运行页面底部的 Artifacts 下载 “广州地铁-apk”，解压得到 APK，发到手机安装。

## 用 Android Studio 生成 APK

打开本文件夹，等待同步完成后，菜单 Build → Build App Bundle(s) / APK(s) → Build APK(s)。

## 说明

- 工程带固定签名证书（`app/gzmetro.keystore`），新版本可以直接覆盖安装，导入的录音不会丢。仓库请设为私有。
- 首次打开时如果粤语不报站，到手机“设置 → 文字转语音”里下载粤语语音包。
- App 名称和图标仅供个人使用，请勿上架应用商店。
