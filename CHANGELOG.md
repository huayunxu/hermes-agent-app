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
- API Key 字段（`API_SERVER_KEY`）与 Web UI 代理模式（无 Key 时走 `/api/hermes/v1/*`）
- 无系统 STT 时录音并调用 Gateway `/v1/audio/transcriptions` 转写

### Fixed
- 修复聊天请求重复发送用户消息的问题
- 修复语音回退发送 `[AUDIO:…]` 占位符而非真实转写
- 修复语音模式在无 Google STT 时被错误禁用
- 思考中重复发送、退出登录后 Agent 服务未重置
- TTS 结束后 `voiceState` 卡在 Speaking
- 内网 HTTP 明文连接受 `network_security_config` 限制
- CI Release 工作流缺少 `contents: write` 权限

### Changed
- 登录流程改为调用 `/api/auth/login` 获取 bearer token
- 聊天上下文仅包含 user/assistant 消息，排除系统提示
- 视频模式文案与能力一致（预览可用，实时上传待实现）

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
