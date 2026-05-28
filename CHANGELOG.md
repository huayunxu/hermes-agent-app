# Changelog

所有 notable 变更都会记录在这个文件中。

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
项目遵循 [Semantic Versioning](https://semver.org/spec/v2.0.0.html)。

---

## [Unreleased]

### Added
- 登录页支持用户名+密码认证（替代 Token 登录）
- 双地址登录（LAN/WAN 自动检测）
- 移除硬编码 Gateway 地址，改为动态派生 API URL

### Changed
- 登录流程改为调用 `/api/auth/login` 获取 bearer token
- API Server 配置从 dev_eng profile 迁移到 default profile

---

## [1.0.0] - 2026-05-28

### Added
- 初始版本发布
- 登录页（Token 认证）
- 文字聊天流式渲染
- 语音消息录制与播放
- 语音通话模式
- 视频通话模式
- GitHub Actions 自动构建 Release APK

### Release
- Release #16: v1.0.0 - 原生 Kotlin Android App 登录与 Gateway 连接修复

---

## [Pre-1.0.0] - 2026-05-27

### Added
- 初始项目结构
- Capacitor Vue3 Web UI 版本
- Hermes Agent 集成

---

[Unreleased]: https://github.com/huayunxu/hermes-agent-app/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/huayunxu/hermes-agent-app/releases/tag/v1.0.0
