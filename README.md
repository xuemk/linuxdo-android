# LinuxDo Android

专为 L 站（`linux.do`）打造的 Android 原生客户端，兼顾极致流畅度与长效会话稳定性。

## 核心优势

- **纯正 Android 原生开发**
  基于 `Kotlin` + `Jetpack Compose` 全原生构建，无套壳、包体轻量；采用现代极简视觉与全屏跟手侧滑返回动效，支持浅色 / 深色 / 跟随系统三档外观切换，配合本地分类缓存预热实现冷启动首屏秒开。

- **有效避免并大幅降低 CF 防护拦截与掉登录问题**
  针对 Cloudflare 防护机制深度定制网络层：全应用统一请求特征，持久化守护登录凭据，大幅减少日常浏览中的盾页打断与意外掉登录。

- **完整集成 Web 端核心功能**
  - **浏览与检索**：最新话题流、全部分类切换（含 `Lv.1 / Lv.2 / Lv.3` 等级勋章）、站内关键词搜索。
  - **深度阅读体验**：原生解析渲染帖子富文本与引用块、站内帖子链接直跳、贴吧风「楼中楼」子回复（默认 3 条预览、一键展开与收起锚定）、长帖尾部切片一步直达、帖子详情下拉刷新与评论自动增量同步。
  - **互动与创作**：支持账号密码 / 邮箱验证码登录、**2FA 两步验证（TOTP 动态口令与恢复码）**、话题与楼层回复、内容编辑与删除、图片附件上传与原图保存。
  - **消息通知与在线升级**：顶栏未读角标实时轮询、通知列表类型角标与标签，支持应用内版本更新检查（可选升级 / 强制必升）。

## 最新版本下载

- **当前版本**：`v1.1.0`
- **APK 下载**：[前往 Releases 下载 `linuxdo-1.1.0-release.apk`](https://github.com/xuemk/linuxdo-android/releases/latest)

> [!CAUTION]
> <span style="color: #ff3b30;">**P.S. 目前版本使用中阅览帖子无法增加网站阅读量等，请关注账号活跃情况**</span>

## 致谢 / Acknowledgements

- **L站原贴**：https://linux.do/t/topic/2998703
- 感谢 [LINUX DO](https://linux.do/) 社区佬友们的建议与支持。
- Special thanks to the [LINUX DO](https://linux.do/) community for feedback and support.
