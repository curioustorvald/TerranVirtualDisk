# TerranVirtualDisk

Virtual Filesystem with built-in finder (aka explorer by Windows folks).

## How to use

Interactions with virtual disk will be done with ```VDUtil```.

Run the .jar file to launch the Finder.

## Features

- Built-in finder
- Directories (managed by Doubly Linked List)
- Symbolic links
- Timestamp of creation and modification, and is 48-Bit
- CRC

## Limitation

- Each directory cannot hold more than 65 535 entries
- Each file or the capacity of the disk can be no larger than 256 TiB.
- Name of the file/directory is limited to 256 bytes
- Name of the disk is limited to 32 bytes
- Total number of entries cannot exceed 4 294 967 294 (you will most likely run out of disk space before reach this number)
- Timestamp will overflow after [8.9 million years](https://www.wolframalpha.com/input/?i=unix+epoch+%2B+2%5E48+seconds)

## Documentation

- Specifications: read [```Spec.md```](Spec.md)
- Javadoc: TBA
