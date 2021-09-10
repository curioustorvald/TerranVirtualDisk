# Terran Virtual Disk Image Format Specification

current specversion number: 0x03

## Changes

### 0x04
- Removed compressed file (TODO instead we're providing compression tool)
- Footer moved upto the header (thus freeing the entry id 0xFEFEFEFE)

### 0x03
- Option to compress file entry

### 0x02
- 48-Bit filesize and timestamp (Max 256 TiB / 8.9 million years)
- 8 Reserved footer

### 0x01
**Note: this version were never released in public**
- Doubly Linked List instead of Singly


## Specs

* File structure


    Header
    
    <entry>
    
    <entry>
    
    <entry>
    
    ...


* Order of the indices does not matter. Actual sorting is a job of the application.
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
    Int8        0xFE
    Int8        Disk properties flag 1
                0th bit: readonly
    Int8[15]    Extra info bytes
    
    (Header size: 64 bytes)



##  IndexNumber and Contents
    <Entry Header>
    <Actual Entry>

NOTES:
- entries are not guaranteed to be sorted, even though the disk cracker will make it sorted.
- Root entry (ID=0) however, must be the first entry that comes right after the header.
- Name of the root entry is undefined, the DiskCracker defaults it as "(root)", but it can be anything.
- Parent node of the root is undefined; do not make an assume that root node's parent is 0.

###  Entry Header
    Int64       EntryID (random Integer). This act as "jump" position for directory listing.
                NOTE: Index 0 must be a root "Directory"
    Int64       EntryID of parent directory
    UInt8       Flag for file or directory or symlink
                0b d000 00tt, where:
                tt - 0x01: Normal file, 0x02: Directory list, 0x03: Symlink
                d - discard the entry if the bit is set
    UInt8[3]    <Reserved>
    Uint8[256]  File name (UTF-8 is recommended)
    Int48       Creation date in real-life UNIX timestamp
    Int48       Last modification date in real-life UNIX timestamp
    Int32       CRC-32 of Actual Entry

    (Header size: 292 bytes)

###  Entry of File (Uncompressed)
    Int48       File size in bytes (max 256 TiB)
    <Bytes>     Actual Contents
    
    (Header size: 6 bytes)

###  Entry of Directory
    Uint32      Number of entries (normal files, other directories, symlinks)
    <Int64s>    Entry listing, contains IndexNumber
    
    (Header size: 4 bytes)

###  Entry of Symlink
    Int64       Target IndexNumber
    
    (Content size: 8 bytes)
