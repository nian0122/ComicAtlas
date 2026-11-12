package domain

// ReadingCompleted 以当前有效目录和媒体为准，单章末页不能代表整部作品读完。
func ReadingCompleted(lastChapter, lastPage bool) bool { return lastChapter && lastPage }
