# 小Ke商城协作规则

- Use `pnpm`; do not use npm or yarn.
- Run commands from the repository root.
- Preserve unrelated changes in the working tree.
- Ask before adding production dependencies.
- If a check cannot run, explain why instead of claiming it passed.
- Summarize changed files and validation results when finished.

## 功能完成后的自动图文讲解

实现完一个新功能、模块或阶段后，在最终交付前读取并执行 [.agents/skills/feature-explainer/SKILL.md](.agents/skills/feature-explainer/SKILL.md)，自动生成可离线阅读的图文 HTML，并在最终回复中提供文件链接。

图文 HTML 与《详细实现说明.md》统一存放在仓库同级的 `../基础模块后阶段计划/<对应阶段目录>/`，分别命名为 `<功能名称>.html` 和 `详细实现说明.md`。登记时用 `--stage` 指定对应阶段目录；交付检查验证两者存在、非空且位置正确，最终回复提供两个文件的链接。

普通问答、计划讨论、只读审查、提交推送、零散修正及此生成流程自身的维护不触发。用户本次明确要求跳过 HTML 时，遵从用户。本项目规则对后续聊天同样适用。
