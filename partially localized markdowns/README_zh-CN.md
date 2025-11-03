# JetBrains IDE 系列的 MicroPython 工具插件（支持 PyCharm / CLion / IntelliJ 等）

[![JetBrains IntelliJ Downloads](https://img.shields.io/jetbrains/plugin/d/26227-micropython-tools?label=Downloads)](https://plugins.jetbrains.com/plugin/26227-micropython-tools)
[![JetBrains IntelliJ Rating](https://img.shields.io/jetbrains/plugin/r/rating/26227-micropython-tools?label=Rating)](https://plugins.jetbrains.com/plugin/26227-micropython-tools)
[![Reviews](https://img.shields.io/badge/Reviews-10-brightgreen)](https://plugins.jetbrains.com/plugin/26227-micropython-tools)

**Translations:** [English](README.md) | [Simplified Chinese (简体中文)](README_zh-CN.md)

这个插件将 MicroPython 支持引入 JetBrains 系列 IDE。
它提供了可靠的设备文件系统集成、REPL 支持、存根包管理，以及流畅的工作流程，
以便开发者能够顺利开展业余或专业的 MicroPython 项目。

该项目最初受 JetBrains 官方 MicroPython 插件启发，但随后经过完全重构并扩展为一个独立工具。
特别感谢 [Jos Verlinde](https://github.com/Josverl/micropython-stubs), [Ilia Motornyi](https://github.com/elmot), [Andrey Vlasovskikh](https://github.com/vlasovskikh)

## 安装、开始和文档

使用提示和有关文档可以在
[此处查询](https://github.com/lukaskremla/micropython-tools-jetbrains/blob/main/DOCUMENTATION_zh-CN.md).

## 主要特点

### 文件系统控件

- 轻松查看并与设备文件系统交互
- 通过拖拽即可上传文件或整理文件系统
- 支持挂载卷（例如SD 卡），并显示存储使用情况![File System Widget](media/file_system.png)

#### 文件系统操作

- 创建新的文件、文件夹。
- 完整可用的复制、剪切、粘贴操作（仅支持从项目到设备，或设备与设备之间）
- 从设备上下载源码
- 打开并且编辑在设备上的文件
  ![File System Widget Context Menu](media/file_system_context_menu.png)
- 编辑或者刷新目前打开的设备上的文件
  ![File System Widget Edit File](media/file_system_edit_file.png)

### REPL控件

- 与MicroPython REPL交互
- 所有键盘快捷键都会原样的传递到设备（支持 Raw REPL、粘贴模式等）
  ![REPL Widget](media/repl.png)

### 上传

- 文件可通过右键菜单操作、拖放或运行配置上传
- 自动跳过已上传的文件（基于CRC32校验）
- 提供上传预览对话框，可直观显示上传完成后的文件系统结构![Upload Preview](media/upload_preview.png)

### 运行配置

- #### 上传
    - 灵活选择需要上传的内容
    - 将设备文件系统同步为仅包含上传的文件和文件夹
    - 支持在同步时排除设备上的特定路径![Upload Run Configuration](media/run_configuration_upload.png)
- #### 在REPL中执行
    - 执行一个".py", ".mpy" 结尾的文件或在REPL中指定的代码选段，而不用上传到设备上。
      ![Execute in REPL Run Configuration](media/run_configuration_execute.png)
      ![Execute Code Fragment in REPL](media/execute_fragment.png)

### MicroPython的Stubs

- 内置Stubs管理集成了由 [Jos Verlinde](https://github.com/Josverl/micropython-stubs)提供的所有可用于MicroPython的包。

### 设置

## MicroPython的Stubs

- 按需安装并应用由[Jos Verlinde](https://github.com/Josverl/micropython-stubs)提供的MicroPython Stubs
- 插件会自动跟踪Stubs版本，并在有可用更新时提示你。

![Settings](media/settings.png)

### 右键上下文菜单

- 快速上传或执行指定的文件
  ![Context Menu File Actions](media/file_actions.png)
- 自定义“标记为 MicroPython 源根”操作，可兼容大多数 JetBrains系IDE。
  ![Context Menu MicroPython Sources Actions](media/micropython_sources.png)

## 要求

* 一个3.10+的Python有效解释器
* Python社区插件（对于那些非PyCharm的IDE）
* 一个安装了MicroPython的开发版（版本1.20+）

本插件基于Apache 2协议授权。
