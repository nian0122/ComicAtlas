package ports

import "context"

// SourceObservation 的生存期限定为一本的准备与提交；文件工作不进入 SQL 事务。
type SourceObservation interface {
	Sources
	Recheck(context.Context) error
}

type Observations interface {
	Observe() SourceObservation
}
