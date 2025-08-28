# 适用于PyCharm, CLion, IntelliJ以及其他JetBrains系IDE的MicroPython工具

[![JetBrains IntelliJ Downloads](https://img.shields.io/jetbrains/plugin/d/26227-micropython-tools?label=Downloads)](https://plugins.jetbrains.com/plugin/26227-micropython-tools)
[![JetBrains IntelliJ Rating](https://img.shields.io/jetbrains/plugin/r/rating/26227-micropython-tools?label=Rating)](https://plugins.jetbrains.com/plugin/26227-micropython-tools)

本项目是[JetBrains IntelliJ MicroPython plugin](https://github.com/JetBrains/intellij-micropython)的一个fork。
感谢[Andrey Vlasovskikh](https://github.com/vlasovskikh)创建的原创插件以及
[Ilia Motornyi](https://github.com/elmot) 关于通讯层和文件系统的原创工作。

同时非常感谢 [Jos Verlinde](https://github.com/Josverl/micropython-stubs) 创建和维护本项目使用的MicroPython stubs。

由于原本的Jetbrains插件开发缓慢，我已经决定fork它并且我想专注于支持
更多我基于本人开发MicroPython的专业经验，认为是无价的特性。

我相信MicroPython社区需要一个健壮的、维护频繁的以及开发好的工具，去把MicroPython

的支持添加到现代工业化标准的IDE中。我的这个Fork就是为了解决这个愿景。

本项目将会频繁的更新，我将频繁参与到开发新功能以及修复已存在的代码中的Bug。如果你在使用这个插件的时候遇到了问题，请开一个Issue。对于任何建议、意见或者是想要新功能，你可以随意的开始讨论。

一些你可以期待马上就能实装的功能：

- Stubs管理器的重置版，允许按需下载和更新Stubs。
- 集成mpy-cross去编译机器码。

长期计划：

- 内置MicroPython固件刷写支持。
- 在这个插件完全发布后，我可能会考虑开发针对于VSCode、或者Visual Studio 2022的MicroPython插件

## 安装，开始和文档

在[这里](https://github.com/lukaskremla/micropython-tools-jetbrains/blob/main/DOCUMENTATION_zh-CN.md)可以找到十分有用的提示和文档。

## 主要功能

### 文件系统组件

- 十分简易的查看、交互设备文件系统上的文件。
- 通过拖动来进行上传或者是重新组织文件系统。
- 支持挂载其他存储介质（比如SD卡），并且显示存储占用。
  ![File System Widget](media/file_system.png)

### REPL组件

- 与MicroPython的REPL交互
- 所有的键盘快捷键都将会直接发送为设备（比如Raw REPL，复制模式等）
  ![REPL Widget](media/repl.png)

### 上传

- 项目可以通过内容管理器、拖动或者运行配置来进行上传
- 已经上传的文件会被自动跳过（使用CRC32）
- 上传预览对话框展示了文件系统在上传文件之后将会变成什么样。
  ![Upload Preview](media/upload_preview.png)

### 运行配置

- #### 上传
    - 轻松的选择哪些文件要被上传
    - 同步设备文件系统，使其仅包含上传的文件和文件夹
    - 在同步时排除掉某个设备上的路径
      ![Upload Run Configuration](media/run_configuration_upload.png)
- #### 在REPL中运行
    - 在不向设备上传任何内容的情况下于REPL中执行“.py”、“.mpy”文件或选定的代码片段。
      ![Execute in REPL Run Configuration](media/run_configuration_execute.png)
      ![Execute Code Fragment in REPL](media/execute_fragment.png)

### MicroPython的Stubs

- 内置Stubs管理集成所有可用的MicroPython Stubs包（作者： [Jos Verlinde](https://github.com/Josverl/micropython-stubs)）

### 上下文菜单操作

- 快速上传或者执行指定的文件
  ![Context Menu File Actions](media/file_actions.png)
- 自定义 "Mark as MicroPython Sources Root"操作，使得其允许与许多不同的JetBrains IDE兼容
  ![Context Menu MicroPython Sources Actions](media/micropython_sources.png)

### 设置

![Settings](media/settings.png)

## 要求

* 一个有效的Python 3.10+ 的解释器
* Python社区组件 (对于非PyCharm的IDE)
* 一块安装了MicroPython固件的开发版 (版本1.20+)

本插件基于Apache 2协议授权。
