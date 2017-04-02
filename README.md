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
- Each file can be no larger than 4 Gigabytes
- Capacity of the disk cannot exceed 4 Gigabytes
- Name of the file/directory is limited to 256 bytes
- Name of the disk is limited to 32 bytes
- Total number of entries cannot exceed 4 294 967 295
- Timestamp will overflow after 292277026596-12-04T15:30:08

## Documentation

- Specifications of the filesystem: read ```Spec.md```
- Javadoc: TBA
