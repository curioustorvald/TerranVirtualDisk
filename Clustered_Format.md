# Virtual Block Filesystem

<aside>
💡 Comes with all the inconveniences of FAT!
</aside>

The filesystem is consisted of 4096 bytes blocks — a number coincides with the cluster size of the modern “host” filesystem.

Everything will be a block — the header, file allocation table, the actual file, etc.

Endianness: Big

# Blocks Overview

| Offset | Description |
| --- | --- |
| 0 | Header |
| 1 | Bootsector |
| 2..3(7,15,31,63,…) | File Allocation Table |
| varies | File |

# Blocks

The Block ID is the block “offset” from the beginning of the archive.

An archive can have 16777216 blocks (max 64 GB)

## Header Block

1 Block size

### Structure

| Offset | Type       | Description |
| --- |------------| --- |
| 0 | **“TEVd”**     | Magic |
| 4 | Uint48     | Disk size in bytes |
| 10 | Byte[32]   | Disk name |
| 42 | Int32      | CRC-32 |
| 46 | **0x11**       | Version |
| 47 | Byte       | Primary Attribute |
| 48 | Byte[16]   | User-defined Attributes |
| 64 | Uint32     | FAT size in blocks |
| 68 | Byte[4028] | Unused |
- Primary Attribute (1 byte)


| — | — | — | Fixed-size? | — | — | — | Read-only? |
| --- | --- | --- | --- | --- | --- | --- | --- |

## Bootsector Block

1 Block size, “raw” formatted (has no structure, all 4096 bytes holds the actual data)

## File Allocation Table Block

16 entries per block with each entry is 256 bytes long

Block growing must be done in a way that (# of FAT + 2) is always power of 2

e.g. FAT count must be 2→6→14→30→62→126→254→…

### File Entry Structure

| Pointer to Block (3 bytes) | Flags (1 byte) | Filename (252 bytes) |
| --- | --- |----------------------|
- Flags
    - TODO
- Filename
    - 252 bytes, null-terminated except for the 252-char long name

## File Block

### Structure

| Meta (2 bytes) | Prev Ptr (3 bytes) | Next Ptr (3 bytes) | Contents (4088 bytes) |
| --- | --- | --- |-----------------------|

Ptr of 0 is used to mark NULL

- Meta bits

| Deleted? | — | — | Dirty? | Type ID (0..15) | Unused (8 bits) |
| --- | --- | --- | --- | --- |-----------------|

| Type ID | Description |
| --- | --- |
| 0 | Binary File |
| 1 | Directory |

### Directory File Contents
| entry size (2 bytes) | repetitoin: cluster numbers (3*n bytes) |
| --- |-----------------------------------------|

entry size cannot exceed 1362 (clusters that actually fits in the remaining bytes — it the length of the directory is longer than that, extra entries must be specified on the next block of the file)

# Functions

## Disk Management Functions

### private renum(increment: Int)

Increments every number (block ptr) by given value.

### public defrag(option: Int = 0)

Removes gaps between blocks if `option` is 0.

OPTIONAL — if `option` is 1, also makes the file blocks contiguous. Only beneficial if the host drive is rotational; SSDs will take no benefit and instead be worn down unnecessarily.

### public growFAT()

Grows the size of the FAT to the next notc.h. Renum must be called afterwards