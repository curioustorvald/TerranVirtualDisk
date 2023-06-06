# Terran Virtual Disk Image Format Specification

current specversion number: 0x03

## Changes

### 0x03
- Structural Change: no more footers
- To convert the version 2 disk into version 3, perform the following on a hex editor
  1. append 17 bits after the version number
  2. overwrite 47th byte to the footer's first byte
  3. remove the footer entirely (FE FE FE FE .. .. .. FF 19)
  4. overwrite 46th byte to 0x03

### 0x02
- 48-Bit filesize and timestamp (Max 256 TiB / 8.9 million years)
- 8 Reserved footer bytes (excluding footer marker and EOF)

### 0x01
**Note: this version were never released in public**
- Doubly Linked List instead of Singly


## File Structure

```
The Archive:
+--------------+
|    Header    |    where,
+--------------+
|              |    Header:            Disk Entry:           Contents:
|  0th Entry   |    +-------------+    +----------------+    - File:
|              |    |   "TEVd"    |    |    Entry ID    |    +-------------+
+--------------+    +-------------+    +----------------+    |  File Size  |
|              |    |  Disk Size  |    |   Parent ID    |    +-------------+
|  Disk Entry  |    +-------------+    +----------------+    |   Payload   |
|              |    |  Disk Name  |    |   Type Flag    |    +-------------+
+--------------+    +-------------+    +----------------+
|              |    |   CRC-32    |    |    Filename    |    - Directory:
|  Disk Entry  |    +-------------+    +----------------+    +-------------+
|              |    |   Version   |    |  CreationDate  |    |ChildrenCount|
+--------------+    +-------------+    +----------------+    +-------------+
|              |    | Attributes  |    |ModificationDate|    | ID of Child |
|     ...      |    +-------------+    +----------------+    +-------------+
|              |                       |     CRC-32     |    | ID of Child |
+--------------+     0th Entry:        +----------------+    +-------------+
                     DiskEntry with    |                |    |     ...     |
                     ID of zero,       |                |    +-------------+
                     directory type    |    Contents    |
                                       |                |    - Symlink:
                                       |                |    +-------------+
                                       +----------------+    |  Target ID  |
                                                             +-------------+
```

* Endianness: Big

##  Header
    Uint8[4]    Magic: TEVd
    Int48       Disk size in bytes (max 256 TiB)
    Uint8[32]   Disk name
    Int32       CRC-32
                1. create list of arrays that contains CRC
                2. put all the CRCs of entries
                3. sort the list (here's the catch -- you will treat CRCs as SIGNED integer)
                4. for elems on list: update crc with the elem (crc = calculateCRC(crc, elem))
    Int8        Version
    Uint8       Disk Attributes
                  0b 7 6 5 4 3 2 1 0
                  0th bit: Readonly
    Uint8[16]   Extra Attributes (user-defined)

    (Header size: 64 bytes)



##  DiskEntry
    <Entry Header>
    <Actual Entry>

NOTES:
- entries are not guaranteed to be sorted, even though the disk cracker will make it sorted.
- Root entry (ID=0) however, must be the first entry that comes right after the header.
- Name of the root entry is undefined, the DiskCracker defaults it as "(root)", but it can be anything.
- Parent node of the root is undefined; do not make an assume that root node's parent is 0.
- The entry which its id is equal to its parent (not just the root) are considered valid and must not be purged. This is the official way to create a hidden file.
  - tsvm expects Entry ID of 1 for the bootloader

###  Entry Header
    Int32       EntryID (random Integer). This act as "jump" position for directory listing.
                NOTE: Index 0 must be a root "Directory"
    Int32       EntryID of parent directory
    Int8        Flag for file or directory or symlink (cannot be negative)
                0x01: Normal file, 0x02: Directory list, 0x03: Symlink
    Uint8[256]  File name in UTF-8
    Int48       Creation date in real-life UNIX timestamp
    Int48       Last modification date in real-life UNIX timestamp
    Int32       CRC-32 of Actual Entry

    (Header size: 281 bytes)

###  Entry of File (Uncompressed)
    Int48       File size in bytes (max 256 TiB)
    <Bytes>     Actual Contents
    
    (Header size: 6 bytes)

###  Entry of Directory
    Uint16      Number of entries (normal files, sub-directories, symlinks)
    <Int32s>    Entry listing, contains IndexNumber
    
    (Header size: 2 bytes)

###  Entry of Symlink
    Int32       Target IndexNumber
    
    (Content size: 4 bytes)
