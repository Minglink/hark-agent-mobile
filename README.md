# Hark Agent Mobile (Hark)

<p align="center">
  <img src="src/shared/icon-512.png" alt="Hark Logo" width="120" style="border-radius: 24px;" />
</p>

<p align="center">
  <b>极速、全能、真正拥有独立 Linux 终端运行环境的移动端自主 AI Agent</b>
</p>

<p align="center">
  <a href="https://github.com/Minglink/hark-agent-mobile/releases"><img src="https://img.shields.io/badge/Release-v1.0.0-blue.svg?style=flat-square" alt="Version 1.0.0" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-GPLv3-green.svg?style=flat-square" alt="License: GPL v3" /></a>
  <a href="#"><img src="https://img.shields.io/badge/Platform-Android%20%7C%20iOS-lightgrey.svg?style=flat-square" alt="Platforms" /></a>
</p>

---

## 🌟 什么是 Hark Agent Mobile？

**Hark** 是专为移动终端设计的强大 AI 智能体应用。与单纯的聊天机器人或套壳 API 客户端不同，**Hark 为 AI 赋予了一台属于它自己的真实移动端计算机与 Linux 沙箱环境**。

模型不仅能够对话，还能主动安装软件、编写与运行脚本、自动化浏览网页、自发规划复杂多步骤任务、管理上下文记忆，并与移动端本地设备能力深度协同。

---

## 🔥 核心特性与能力

### 1. 真正的设备端独立 Linux 沙箱（PRoot / iSH）
- **真·终端环境**：内置沙箱运行真实的 Alpine Linux 用户空间。
- **开箱即用包管理**：支持通过 `apk add` / `pip install` 安装任意 CLI 工具（Python、Git、cURL、Wget 等）并在独立进程中无冲突执行。
- **后台命令流与实时监控**：配备独立的 CPU/MEM 资源 HUD 与控制台输出折叠。

### 2. 多模型矩阵与统一 Agent 调度
- **主流顶尖模型全支持**：支持 Claude 系列、OpenAI、Gemini、DeepSeek、OrcaRouter 等多个 API 提供商。
- **原生用量与额度看板**：内置实时余额与用量监控，支持多通道余额适配、30秒缓存及主动刷新。
- **自动 Fallback 容灾**：当主选模型遭遇限流或超时时，无缝降级切换至备选模型组。

### 3. 自主任务规划引擎（TodoWrite Task Engine）
- **任务目标状态机**：Agent 在面对复杂或多阶段任务时，会自动调用 `todo_write` 工具建立清晰的任务执行计划。
- **移动端交互进度卡片**：在聊天窗口上方以动画折叠卡片展示即时进度、完成状态（`[x]`, `[~]`, `[ ]`）与高优先级标签，任务推进一目了然。

### 4. 智能上下文压缩（Micro-Compaction）
- **智能修剪陈旧输出**：在多轮复杂交互中，自动保留最近活跃交互，将历史超长工具调用结果进行摘要化轻量剪裁，彻底杜绝 Token 膨胀与上下文浪费，兼顾推理速度与记忆连贯性。

### 5. 项目规则与规范感知（`HARK.md` / `CLAUDE.md`）
- **开发规范注入**：在工作区中放置 `HARK.md` 或 `CLAUDE.md`，Agent 在执行工作时将自动感知并遵循项目规范、代码风格与特定指令。

### 6. 浏览器级网页自动化（Browser Use）
- **无头/可视化双模**：支持多标签管理、DOM 树结构分析、网页内容提取、截图快照，以及模拟点击与滚动等交互。

---

## 📲 下载与安装

您可直接前往本仓库的 **[Releases 页面](https://github.com/Minglink/hark-agent-mobile/releases)** 下载最新的预编译安装包：

- **Hark-1.0.0.apk**（全架构打包，内置完整 arm64 PRoot 内核与 minirootfs 文件系统）

---

## 🛠️ 从源码编译 (Android)

### 环境要求
- **JDK**：17
- **Android SDK**：compileSdk 36, targetSdk 35, minSdk 26
- **Android NDK**：r28+
- **CMake**：3.22.1+

### 编译步骤
```bash
# 1. 克隆代码仓库
git clone https://github.com/Minglink/hark-agent-mobile.git
cd hark-agent-mobile

# 2. 编译 Android Release APK
cd src/android
./gradlew :app:assembleRelease

# 生成的 APK 位于:
# src/android/app/build/outputs/apk/release/app-release.apk
```

---

## 📂 代码库结构

```text
hark-agent-mobile/
├── src/
│   ├── android/       # Android 应用源码 (Kotlin + Jetpack Compose + JNI)
│   ├── ios/           # iOS 应用源码 (Swift + SwiftUI)
│   └── shared/        # 跨端共享资源与图标
├── deps/              # 本地沙箱与底层依赖构建脚本
├── scripts/           # 沙箱 rootfs 准备与开发工具脚本
└── README.md          # 项目文档与介绍
```

---

## 📄 开源许可证

本项目基于 **[GNU General Public License v3.0 (GPLv3)](LICENSE)** 开源。
第三方依赖及其许可证信息详见 [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)。
