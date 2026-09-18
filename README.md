# JoyWskeyCopy

京东 `wskey` 本地提取模块。**仅作用于自己的账号，仅写入本地剪贴板，无任何网络代码。**

## 与原 `joy_login_sync.apk` 的区别

| | 原 APK | 本模块 |
|---|---|---|
| 网络上传 | 有，POST 到 `robot.djun97.top` | **无**，代码中不存在网络调用 |
| 权限 | 需要 INTERNET | **不申请任何权限** |
| 作用域 | 京东 | 京东 |
| 抓取方式 | 反射 + 混淆 `.so` 动态取 | hook 存储 + 网络 Cookie，两条路兜底 |
| 拿到后 | 上传到服务器 | 写剪贴板 + Toast「ck已复制」 |

## 抓取原理

两条独立的路径，谁先命中就立刻复制并停止后续工作：

1. **本地存储**：hook `SharedPreferencesImpl` 的 `getString` / `getAll`，
   从键值里找 `wskey`。
2. **网络层**：hook `okhttp3.Request$Builder.build` 和
   `URLConnection.setRequestProperty`，从 `Cookie` 头里提取 `wskey=...`。

命中判定：值里含 `wskey=` 则取等号后到 `;` / 空格为止的部分；或键名含
`wskey` 且值形如凭证（长度 ≥16，仅字母数字与 `* - _`）。

## 编译（GitHub Actions 云端构建，无需本机装环境）

1. 在 GitHub 上新建一个仓库（**Private 也可以**）。
2. 把本目录**全部内容**推到仓库的 `main` 分支：

```
git init
git add .
git commit -m "init"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

> 注意：`gradle/wrapper/gradle-wrapper.jar` 和 `app/libs/XposedBridgeApi-82.jar`
> 这两个二进制文件**必须一起提交**，否则 Actions 会构建失败。

3. 推送后自动触发构建。到仓库的 **Actions** 页看进度。
4. 构建成功后，在对应 run 页面底部的 **Artifacts** 里下载
   `JoyWskeyCopy-release`，解压得到 **已签名的 APK**，可直接安装。

也可以手动触发：Actions → Build APK → Run workflow。

### 签名信息

Actions 每次构建都会用固定口令自动生成密钥并签名：

| 项 | 值 |
|---|---|
| keystore | `release.keystore` |
| alias | `joykey` |
| storepass / keypass | `joywskeycopy` |
| 有效期 | 10000 天 |

> 这是**明文测试口令**，仅用于让你拿到可直接安装的 APK。
> 如果以后要长期维护、覆盖安装，建议换成你自己的密钥：
> 把 keystore 用 base64 存进仓库 Secrets，再改 workflow 的签名步骤。

### 本地构建（可选）

若本机装了 JDK 17 + Android SDK：

```
gradlew assembleRelease
```

依赖：Xposed API 已内置为 `app/libs/XposedBridgeApi-82.jar`
（`compileOnly`，只参与编译，**不会打进 APK**）。


## 安装与使用

1. 确认设备已 Root 并装有 **LSPosed**（或 EdXposed，需开启
   「应用作用域」兼容）。
2. 安装签名后的 APK。
3. 在 LSPosed 中启用本模块，作用域勾选**京东**。
4. 强制停止京东（或重启），重新打开京东并进入已登录状态。
5. 正常浏览任意页面，触发一次请求 → 模块抓取 `wskey` → 剪贴板自动写入
   并弹出「ck已复制」。
6. 到任意输入框粘贴即可。

## 排查

查看日志：

```
adb logcat | findstr WskeyCopy
```

- `未找到 okhttp3，跳过` — 京东可能对 okhttp 做了改名/加固，此时依赖
  SharedPreferences 那条路；若两条都无输出，把 key 名告诉我再加针对性规则。
- `没有拿到 Context` — 京东进程启动早于 Application.onCreate，重开一次
  京东即可。
- 只命中一次后不再抓取是**设计如此**（避免重复刷屏与性能损耗）。
  想重新抓取，重启京东进程。

## 安全提示

- 剪贴板内容对系统内其他应用可读，复制后建议尽快粘贴并清空剪贴板。
- `wskey` 是长期登录凭证，**不要发给任何人、不要贴到聊天工具或网页里**。
- 若曾安装过原 `joy_login_sync.apk`，请先在京东内退出登录并改密码，
  使旧的 `wskey` 失效。
