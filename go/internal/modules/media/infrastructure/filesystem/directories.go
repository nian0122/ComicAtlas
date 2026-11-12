package filesystem

import "path"

// syncParents 在发布或确认新文件前同步每级目录，不把文件同步等同于目录项持久化。
func (storage *Storage) syncParents(name string) error {
	for current := path.Dir(name); current != "."; current = path.Dir(current) {
		if err := storage.syncDirectory(current); err != nil {
			return err
		}
	}
	return storage.syncDirectory(".")
}
