package domain

import mediadomain "comicatlas.local/backend/internal/modules/media/domain"

const MaxMediaBytes int64 = 20 << 30

// 原件、当前派生文件和去重父目录的逐本身份预算，不缓存文件内容或全库名单。
const MaxSourceObservations = 400000

// Measurement 仅证明一次读取的结果，不代替数据库提交前的来源复核。
type Measurement struct {
	Path, SHA256 string
	Size         int64
	Image        *mediadomain.Inspection
	Video        *mediadomain.Video
}

// MediaVerification 只统计已核对字节的文件，不代表编码或恢复写入已通过。
type MediaVerification struct {
	Originals, Derivatives, Released, IgnoredDerivatives int
	MissingOriginals, UnboundOriginals                   int
	Bytes                                                int64
}
