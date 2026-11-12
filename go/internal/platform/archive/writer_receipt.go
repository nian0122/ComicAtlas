package archive

import "os"

// WriteReceipt 仅在本次进程内复用完整校验结果，不写入任务清单。
type WriteReceipt struct{ Info os.FileInfo }
