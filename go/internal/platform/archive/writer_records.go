package archive

const zip32Max = uint64(1<<32 - 1)

type writeRecord struct {
	directory  bool
	dataOffset uint64
	sourceSHA  string
	name       string
	size       uint64
	crc        uint32
	disk       uint16
	offset     uint64
	force64    bool
}

func (record writeRecord) size64() bool { return record.force64 || record.size >= zip32Max }
func (record writeRecord) version() uint16 {
	if record.size64() || record.offset >= zip32Max {
		return 45
	}
	return 20
}
func (record writeRecord) extra(central bool) []byte {
	var values []uint64
	if record.size64() {
		values = append(values, record.size, record.size)
	}
	if central && record.offset >= zip32Max {
		values = append(values, record.offset)
	}
	if len(values) == 0 {
		return nil
	}
	result := make([]byte, 4+8*len(values))
	le.PutUint16(result, 1)
	le.PutUint16(result[2:], uint16(8*len(values)))
	for index, value := range values {
		le.PutUint64(result[4+8*index:], value)
	}
	return result
}
func (record writeRecord) local() []byte {
	extra := record.extra(false)
	result := make([]byte, 30+len(record.name)+len(extra))
	le.PutUint32(result, 0x04034b50)
	version := uint16(20)
	if record.size64() {
		version = 45
	}
	le.PutUint16(result[4:], version)
	le.PutUint16(result[6:], 1<<11)
	le.PutUint16(result[12:], 33) // 固定 1980-01-01，归档顺序与内容不受宿主时区影响。
	le.PutUint32(result[14:], record.crc)
	size := uint32(record.size)
	if record.size64() {
		size = 0xffffffff
	}
	le.PutUint32(result[18:], size)
	le.PutUint32(result[22:], size)
	le.PutUint16(result[26:], uint16(len(record.name)))
	le.PutUint16(result[28:], uint16(len(extra)))
	copy(result[30:], record.name)
	copy(result[30+len(record.name):], extra)
	return result
}
func (record writeRecord) central() []byte {
	extra := record.extra(true)
	result := make([]byte, 46+len(record.name)+len(extra))
	le.PutUint32(result, 0x02014b50)
	le.PutUint16(result[4:], 3<<8|record.version())
	le.PutUint16(result[6:], record.version())
	le.PutUint16(result[8:], 1<<11)
	le.PutUint16(result[14:], 33)
	le.PutUint32(result[16:], record.crc)
	size := uint32(record.size)
	if record.size64() {
		size = 0xffffffff
	}
	le.PutUint32(result[20:], size)
	le.PutUint32(result[24:], size)
	le.PutUint16(result[28:], uint16(len(record.name)))
	le.PutUint16(result[30:], uint16(len(extra)))
	le.PutUint16(result[34:], record.disk)
	le.PutUint32(result[38:], 0100644<<16)
	if record.directory {
		le.PutUint32(result[38:], 0040755<<16|0x10)
	}
	offset := uint32(record.offset)
	if record.offset >= zip32Max {
		offset = 0xffffffff
	}
	le.PutUint32(result[42:], offset)
	copy(result[46:], record.name)
	copy(result[46+len(record.name):], extra)
	return result
}

func endRecords(last, directoryDisk uint16, count, onDisk uint64, directorySize, directoryOffset, endOffset uint64, zip64 bool) []byte {
	var result []byte
	if zip64 {
		result = make([]byte, 76)
		le.PutUint32(result, 0x06064b50)
		le.PutUint64(result[4:], 44)
		le.PutUint16(result[12:], 3<<8|45)
		le.PutUint16(result[14:], 45)
		le.PutUint32(result[16:], uint32(last))
		le.PutUint32(result[20:], uint32(directoryDisk))
		le.PutUint64(result[24:], onDisk)
		le.PutUint64(result[32:], count)
		le.PutUint64(result[40:], directorySize)
		le.PutUint64(result[48:], directoryOffset)
		le.PutUint32(result[56:], 0x07064b50)
		le.PutUint32(result[60:], uint32(last))
		le.PutUint64(result[64:], endOffset)
		le.PutUint32(result[72:], uint32(last)+1)
	}
	end := make([]byte, 22)
	le.PutUint32(end, 0x06054b50)
	le.PutUint16(end[4:], last)
	le.PutUint16(end[6:], directoryDisk)
	if zip64 {
		le.PutUint16(end[8:], 0xffff)
		le.PutUint16(end[10:], 0xffff)
		le.PutUint32(end[12:], 0xffffffff)
		le.PutUint32(end[16:], 0xffffffff)
	} else {
		le.PutUint16(end[8:], uint16(onDisk))
		le.PutUint16(end[10:], uint16(count))
		le.PutUint32(end[12:], uint32(directorySize))
		le.PutUint32(end[16:], uint32(directoryOffset))
	}
	return append(result, end...)
}
