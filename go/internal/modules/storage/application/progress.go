package application

import "time"

// progressClock 限制扫描进度写库频率。扫描器按条目回调，但慢速卷上每次提交
// 都要同步落盘，逐批写库会把扫描拖慢数倍；这里只在间隔到达时放行一次。
type progressClock struct {
	interval time.Duration
	last     time.Time
}

// due 报告本次回调是否需要写库，并在放行时推进时间点。
func (clock *progressClock) due(now time.Time) bool {
	if !clock.last.IsZero() && now.Sub(clock.last) < clock.interval {
		return false
	}
	clock.last = now
	return true
}
