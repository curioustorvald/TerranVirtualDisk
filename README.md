# TerranVirtualDisk

Virtual Filesystem with built-in editor.

## How to use

Interactions with virtual disk will be done with ```VDUtil```.

## Features

- Built-in finder (```VirtualDiskCracker```)
- Directories (managed by Linked List)
- Symbolic links
- Timestamp of creation and modification
- CRC

## Limitation

- Each directory cannot hold more than 65 535 entries
- Each file can be no larger than 2 Gigabytes
- Capacity of the disk cannot exceed 2 Gigabytes
- Name of the file/directory is limited to 256 bytes
- Name of the disk is limited to 32 bytes
- Total number of entries cannot exceed 4 294 967 295
- Timestamp will overflow after [292 million years](https://www.wolframalpha.com/input/?i=(2%5E63+%2F+1000)+seconds+from+unix+epoch)

## Documentation

- Specifications of the filesystem: read ```Spec.md```
- Javadoc: TBA
