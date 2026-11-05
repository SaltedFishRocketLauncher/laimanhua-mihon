# 来漫画 — Mihon 图源扩展

针对 `laimanhua88.com` 的 Mihon（Tachiyomi）图源插件。

## 安装

在 Mihon 中进入「设置 → 浏览 → 扩展仓库」，粘贴下面的地址：

```
https://raw.githubusercontent.com/SaltedFishRocketLauncher/laimanhua-mihon/repo/index.pb
```

然后在「浏览 → 扩展」里找到「来漫画」并安装。

> 索引为 protobuf 格式（`index.pb`），即 Mihon 当前使用的标准扩展仓库格式。

## 功能

- 热门：合并站点首页全部榜单区块，按 URL 去重
- 最新更新：支持翻页
- 分类筛选：热血 / 格斗 / 科幻 / 少女 等
- 风云榜：多榜单切换
- 搜索：按首字拼音定位站点字母索引页，再本地匹配标题
- 详情页：读取 `og:*` 结构化 meta（标题 / 作者 / 封面 / 简介 / 状态）
- 章节列表：`div.plist li > a`，回退 `#chapterList li a`
- 图片：解析内联 `picTree`（明文或 Base64），按 `$qingtiandy$` 切分
- 图片主机容错 + Referer 处理

## 构建

推送 tag 或手动触发 GitHub Actions，工作流会构建 APK、生成索引并推送到 `repo` 分支。

## 免责

仅供个人学习与技术研究使用。所有内容版权归原作者及原站点所有。
