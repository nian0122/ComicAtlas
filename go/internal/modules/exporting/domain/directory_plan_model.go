package domain

import "bytes"

const MaxDirectoryPlanReadBytes = 64 << 20

// 冻结协议逐行存储，数组先声明有界条数，避免一次编码/解码整个大批次。
type directoryPlanHeader struct {
	Version int
	Books   int
	Seal    DirectorySeal
}

type directoryPlanBook struct {
	Identity                                              DirectoryInventoryIdentity
	Name                                                  string
	Files, Directories, Owned, Absences, Documents, Trash int
}

type directoryPlanBuffer struct{ value bytes.Buffer }
