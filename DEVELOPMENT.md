# Hermes Android App - 开发规范流程

## 1. 分支策略 (Git Flow)

```
main          ← 生产分支，随时可发布
  ↑
develop       ← 开发分支，集成所有功能
  ↑
feature/login ← 功能分支，从 develop 切出
```

| 分支 | 用途 | 保护规则 |
|------|------|----------|
| `main` | 生产代码，对应 GitHub Releases | 需要 PR + 至少 1 人审查 |
| `develop` | 日常开发集成 | 需要 PR |
| `feature/*` | 功能开发 | 完成后合并到 develop |
| `hotfix/*` | 紧急修复 | 合并到 main 和 develop |
| `release/*` | 发布准备 | 测试通过后合并到 main |

## 2. 提交规范 (Conventional Commits)

```
feat:     新功能
fix:      修复 bug
docs:     文档更新
refactor: 代码重构（不影响功能）
test:     添加/修改测试
chore:    构建/工具/配置
ci:       CI/CD 配置
```

示例：
```
feat: add voice call mode with real-time streaming
fix: resolve null pointer in login flow when address is empty
docs: update README with API server configuration
```

## 3. Issue 管理

每个 Issue 必须包含：
- **目标**：清晰描述要完成什么
- **已完成**：[x] 列表追踪进度
- **待完成**：[ ] 列表待办事项
- **关联**：分支、PR、里程碑

Issue 状态流转：
```
open → in_progress → review → closed
```

## 4. PR 流程

```
1. 从 develop 切出 feature 分支
2. 开发 + 本地测试
3. 提交 PR 到 develop
4. CI 自动运行（编译 + 测试）
5. 至少 1 人审查通过
6. Squash merge 到 develop
7. 定期从 develop 发布 release
```

PR 模板要求：
- 描述变更内容
- 关联 Issue (#1)
- 测试截图/日志
- 是否有 breaking change

## 5. CI/CD (GitHub Actions)

```yaml
# .github/workflows/build.yml
on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main, develop]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: gradle/gradle-build-action@v3
      - run: ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          path: app/build/outputs/apk/debug/

  release:
    needs: build
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - uses: actions/create-release@v1
      - uses: actions/upload-release-asset@v1
```

## 6. 版本管理 (Semantic Versioning)

```
v1.0.0  ← 主版本：不兼容的 API 变更
v1.1.0  ← 次版本：新功能（向下兼容）
v1.1.1  ← 修订版本：bug 修复（向下兼容）
```

Release 命名：
```
v1.0.0 - Initial release (Login + Text Chat)
v1.1.0 - Voice Call + Video Call
v1.2.0 - Settings + Model Selection
```

## 7. 文档要求

| 文档 | 位置 | 更新时机 |
|------|------|----------|
| README | 根目录 | 每次重大变更 |
| CHANGELOG | 根目录 | 每次 Release |
| CONTRIBUTING | 根目录 | 项目初始化 |
| API 文档 | docs/api.md | API 变更时 |

## 8. 当前项目状态

| 项目 | 状态 | 说明 |
|------|------|------|
| 分支 | ❌ 只有 main | 需要创建 develop |
| Issue | ✅ 已创建 #1 | 登录认证 Phase 1 |
| PR | ❌ 无 | 需要建立 PR 流程 |
| CI/CD | ✅ 已有 | GitHub Actions 自动构建 |
| Release | ✅ #16 | 当前最新版本 |
| 文档 | ⚠️ 基础 | 需要完善 |

## 9. 下一步行动

1. **立即**：创建 `develop` 分支
2. **立即**：创建 GitHub Project 看板（需要升级 token 权限）
3. **本周**：完善 README + CHANGELOG
4. **本周**：建立 PR 审查流程
5. **持续**：每个 Issue 对应一个 feature 分支

---
*最后更新: 2026-05-28*
*维护者: Yvan (huayunxu)*
