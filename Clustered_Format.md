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

| Offset | Type | Description |
| --- | --- | --- |
| 0 | “TEVd” | Magic |
| 4 | Uint48 | Disk size in bytes |
| 10 | Byte[32] | Disk name |
| 42 | Int32 | CRC-32 |
| 46 | 0x11 | Version |
| 47 | Byte | Primary Attribute |
| 48 | Byte[16] | User-defined Attributes |
| 64 | Uint32 | FAT size in blocks |
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

| Pointer to Cluster (3 bytes) | Flags (1 byte) | Creation Date (6 bytes) | Modification Date (6 bytes) | Filename (240 bytes) |
| --- | --- | --- | --- |----------------------|

- Flags

| Deleted? | — | — | — | — | System? | Hidden? | Read-only? |
| --- | --- | --- | --- | --- | --- | --- | --- |

- Filename
  - 240 bytes, null-terminated except for the 240-char long name

## File Block

### Structure

| Meta (2 bytes) | Prev Ptr (3 bytes) | Next Ptr (3 bytes) | Contents (4088 bytes) |
| --- | --- | --- |-----------------------|

Ptr of 0 is used to mark NULL

- Meta Byte 1


| — | — | — | Dirty? | Type ID (0..15) |
| --- | --- | --- | --- | --- |
    
The 4 flags are reserved for flagging bad clusters
    
| Type ID | Description |
| --- | --- |
| 0 | Binary File |
| 1 | Directory |

- Meta Byte 2

| — | — | — | — | — | — | — | Not a Head Cluster? |
| --- | --- | --- | --- | --- | --- | --- | --- |

In order to figure out the true size of the binary/directory, one must traverse the entire cluster chain.

### Directory File Contents

| entry size (2 bytes) | repetition: cluster numbers (3*n bytes) |
| --- |-----------------------------------------|

- Entry size cannot exceed 1362 (cluster IDs that will actually fit in the remaining space)
- If the length of the directory is longer than that, the extra entries must be written on the next block of the file

### Binary File Contents

| number of bytes in this cluster (2 bytes) | binary data (up to 4086 bytes; unused area shall be filled with 0s but not guaranteed) |
| --- |----------------------------------------------------------------------------------------|

- If the length of the file is longer than 4086 bytes, the extra entries must be written on the next block of the cluster chain

# Functions

## Disk Management Functions

### private renum(increment: Int)

Increments every number (block ptr) by given value.

### public defrag(option: Int = 0)

Removes gaps between blocks if `option` is 0.

OPTIONAL — if `option` is 1, also makes the file blocks contiguous. Only beneficial if the host drive is rotational; SSDs will take no benefit and instead be worn down unnecessarily.

### public growFAT()

Grows the size of the FAT to the next notc.h. Renum must be called afterwards