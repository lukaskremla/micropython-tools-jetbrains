# MicroPython Tools Documentation

## Table of Contents

- [Getting Started](#getting-started)
    - [Installation](#installation)
    - [Setting Up a Run Configuration](#setting-up-a-run-configuration)
- [Uploads](#uploads)
    - [Run Configurations](#run-configurations)
        - [Project](#project)
        - [Selected MicroPython Sources Roots](#selected-micropython-sources-roots)
        - [Custom Path](#custom-path)
    - [Drag and Drop](#drag-and-drop)
    - [Context Menu Actions](#context-menu-actions)

## Getting Started

### Installation

1. Ensure that you have the necessary drivers for communicating with your microcontroller installed.
2. Ensure you have a JetBrains IDE version **2024.3 or newer** with the Python plugin installed.
3. Go to the **Plugin Marketplace**, search for *MicroPython Tools*, and install the plugin.
4. Open a project or create a new one.
5. Go to `Settings → Languages & Frameworks → MicroPython Tools`.
6. Enable **MicroPython support**.
7. Select an appropriate **stub package**.
8. Choose and configure your preferred **communication type**, then apply the settings.

### Setting Up a Run Configuration

To upload files to the device efficiently, you need to set up a run configuration:

1. Go to the **Run Configurations** menu near the top right corner of your IDE.
2. Create a new `MicroPython Tools → Upload` run configuration.
3. Choose a run configuration type (*Project* is best for most use cases).
4. Enable:
    - "Reset on success"
    - "Switch to REPL tab on success"
    - "Synchronize"
5. Configure paths to exclude from synchronization if needed (e.g. logs, OTA directories, persistent config files,
   etc.).
6. Create a new folder, right-click it, and mark it as a **MicroPython Sources Root**.

You can now treat this folder as the file system root `/` of your device and structure your code accordingly.  
When you make changes to your project, you can simply execute this run configuration — all new and modified items will
be uploaded, and any items deleted from your project will also be deleted from the device.

## Uploads

There are several options for uploading items. All of them will skip already uploaded files if the connected board is
capable of calculating CRC32 hashes.

### Run Configurations

Run configurations are the main way to upload files to a MicroPython device. They allow you to upload and synchronize  
the files on the target device with just a single click.

There are three types of run configurations:

1. Project
2. Selected MicroPython Sources Roots
3. Custom Path

They differ in how files and folders to upload are selected. The other features (reset on success, switch to REPL tab,  
and synchronize) behave the same across all of them.

#### Project

This is the run configuration that is going to be the best fit for most use cases. It can work in two ways, depending
on  
whether at least one MicroPython Sources Root is marked.

##### No MicroPython Sources Roots Are Marked

All files and folders in your project will be uploaded with target (on-device) paths relative to the project root. For  
example:

```MyProjectName/Folder1/SubFolder/Script.py``` will be uploaded as ```Folder1/SubFolder/Script.py```

The algorithm will ignore and avoid uploading all items with a leading dot in their name, test source roots, and all  
excluded folders and their children.

##### One or More MicroPython Sources Roots Are Marked

If one or more MicroPython Sources Roots are marked, then only children of the top-most MicroPython Sources Roots will
be  
uploaded to the device relative to them. The files will get uploaded like this:

```MyProjectName/SomeFolder/MpySourcesRootMarkedFolder/Folder1/SubFolder/Script.py``` will be uploaded as  
```Folder1/SubFolder/Script.py```

Similarly to the project run configuration, all items with a leading dot in their name, test source roots, and all  
excluded folders and their children will be skipped.

#### Selected MicroPython Sources Roots

This run configuration works similarly to the project run configuration when at least one MicroPython Sources Root is  
selected. But instead of uploading all top-most MicroPython Sources Roots, you get to select which ones get uploaded.

This is useful in scenarios where you have a folder with ```.py``` files and another with ```.mpy``` files. You can
mark  
each folder as a MicroPython Sources Root and then have two separate `Selected MPY sources roots` run  
configurations — one for uploading ```.py``` files and another for uploading ```.mpy``` files.

Test folders, leading dot items, and excluded folders are ignored the same way as the Project run configuration does it.

#### Custom Path

The last upload run configuration type is Custom Path. This allows you to select a specific folder or file and
manually  
configure where it gets uploaded. This can be useful for uploading `secrets.py` and similar files, or other cases  
where the previous two types aren't appropriate for some reason.

When a file is selected, it gets uploaded with the displayed target path. When a folder gets uploaded, its contents  
will be uploaded to the `Upload to` path. So if you want to upload the whole folder and not just its contents, you  
must specify it in the `Upload to` field.

This run configuration type can also be useful if you want to upload a test source root, which would be ignored by the  
previous two types. Excluded and leading dot items are still skipped.

### Drag and Drop

You can quickly upload items by dragging them from the project file tree to the File System tab in the plugin's tool  
window. The items will get uploaded where they are dropped.

Excluded and leading dot items are still skipped. Test source roots get uploaded if they are explicitly selected.

### Context Menu Actions

You can manually upload files by right-clicking them in the project file tree or in the editor tab of open files. They  
can be uploaded directly to the device root `/`, relative to the project root, or relative to the parent-most  
MicroPython Sources Root (if applicable).

Excluded and leading dot items are still skipped. Test source roots get uploaded if they are explicitly selected.