# 问题记录：本地 Markdown

本仓库的问题与产品需求记录在 `.scratch/` 中，不写入远程 GitHub Issues。

## 约定

- 每个功能使用一个目录：`.scratch/<feature-slug>/`
- 产品需求文档为：`.scratch/<feature-slug>/PRD.md`
- 实现问题为：`.scratch/<feature-slug>/issues/<NN>-<slug>.md`，从 `01` 开始编号
- 每个问题靠近文件顶部的位置使用 `Status:` 记录当前状态，允许值见 `triage-labels.md`
- 评论与讨论历史追加在文件末尾的 `## Comments` 下

## 发布到问题记录器

在 `.scratch/<feature-slug>/` 下创建相应 Markdown 文件，目录不存在时一并创建。

## 获取相关问题

读取用户给出的文件路径或问题编号对应的 Markdown 文件。
