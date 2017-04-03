# Terran Virtual Disk Image Format Specification

* File structure


    Header
    
    IndexNumber
    <entry>
    
    IndexNumber
    <entry>
    
    IndexNumber
    <entry>
    
    ...
    
    Footer


* Order of the indices does not matter. Actual sorting is a job of the application.
* Endianness: Big


##  Header
    Uint8[4]     Magic: TEVd
    Int32      Disk size in bytes (max 2048 MBytes)
    Uint8[32]   Disk name
    Int32      CRC-32
                1. create list of arrays that contains CRC
                2. put all the CRCs of entries
                3. sort the list (here's the catch -- you will treat CRCs as SIGNED integer)
                4. for elems on list: update crc with the elem (crc = calculateCRC(crc, elem))
    
    (Header size: 44 bytes)



##  IndexNumber and Contents
    <Entry Header>
    <Actual Entry>

###  Entry Header
    Int32       Random Int either positive or negative. This act as "jump" position for directory listing.
                NOTE: Index 0 must be a root "Directory".
    Int8        Flag for file or directory or symlink (cannot be negative)
                0x01: Normal file, 0x02: Directory list, 0x03: Symlink
    Uint8[256]  File name in UTF-8
    Int64       Creation date in real-life UNIX timestamp
    Int64       Last modification date in real-life UNIX timestamp
    Int32       CRC-32 of Actual Entry

    (Header size: 281 bytes)

###  Entry of File
    Int32      File size in bytes (max 2048 MBytes)
    <Bytes>     Actual Contents
    
    (Header size: 4 bytes)

###  Entry of Directory
    Uint16      Number of entries (normal files, other directories, symlinks)
    <Int32s>    Entry listing, contains IndexNumber
    
    (Header size: 2 bytes)

###  Entry of Symlink
    Int32       Target IndexNumber
    
    (Content size: 4 bytes)




## Footer
    Uint8[2]    0xFE 0xFF (BOM, footer mark)
    <optional footer if present>
    Uint8[2]    0xFF 0x19 (EOF mark)
