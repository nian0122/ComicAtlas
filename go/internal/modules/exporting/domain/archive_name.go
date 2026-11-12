package domain

// ArchiveName 使用受理快照中的标题，限制长度并避开跨平台文件名限制。
func ArchiveName(snapshot Snapshot) string {
	name := label(snapshot.Comic.Title, 180)
	if !directoryTarget(name) {
		name = "_" + name
	}
	return name + "." + snapshot.Request.Format
}
