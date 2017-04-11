# TerranVirtualDisk

Virtual Filesystem with built-in finder (aka explorer by Windows folks).

## How to use

Interactions with virtual disk will be done with ```VDUtil```.

Run the .jar file to launch the Finder.

## Features

- Built-in finder
- Directories (managed by Doubly Linked List)
- Symbolic links
- Timestamp of creation and modification, and is 64-Bit
- CRC

## Limitation

- Each directory cannot hold more than 65 535 entries
- Each file can be no larger than 2 Gigabytes
- Capacity of the disk cannot exceed 2 Gigabytes
- Name of the file/directory is limited to 256 bytes
- Name of the disk is limited to 32 bytes
- Total number of entries cannot exceed 4 294 967 294 (you will run out of disk capacity before reach this number)
- Timestamp will overflow after [292 million years](https://www.wolframalpha.com/input/?i=(2%5E63+%2F+1000)+seconds+from+unix+epoch)

## Documentation

- Specifications: read ```Spec.md```
- Javadoc: TBA
