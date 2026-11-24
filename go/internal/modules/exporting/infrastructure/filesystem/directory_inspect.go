package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"comicatlas.local/backend/internal/platform/relocate"
	"context"
	"errors"
	"io"
	"os"
	"path"
	"sort"
	"strings"
)

type directoryInspection struct {
	files       *Files
	destination *os.File
	safe        map[string]bool
	volumes     map[string]bool
}

// InspectDirectory 只做全批实际文件预检，不创建任务、不移动原件、不删除未知内容。
func (files *Files) InspectDirectory(ctx context.Context, id string, plans []exportdomain.DirectoryPlan) (exportdomain.DirectoryInspection, error) {
	value := exportdomain.DirectoryInspection{Files: []exportdomain.DirectoryObservation{}}
	if err := ctx.Err(); err != nil {
		return value, err
	}
	if !exportdomain.TaskID(id) {
		return value, exportdomain.ErrValidation
	}
	var err error
	if err = exportdomain.ValidateDirectoryPlans(plans); err != nil {
		return value, err
	}
	if err = files.verifyDirectoryAbsences(ctx, plans); err != nil {
		return value, err
	}
	for _, name := range []string{"exports/" + id, "staging/exports/" + id} {
		if _, err = files.rootFor(name).Lstat(name); err == nil {
			return value, exportdomain.ErrConflict
		} else if !errors.Is(err, os.ErrNotExist) {
			return value, err
		}
	}
	if err = files.safeParents("staging/exports/placeholder"); err != nil {
		return value, err
	}
	if err = files.safeParents("exports/placeholder"); err != nil {
		return value, err
	}
	stage, err := files.root.Open("staging/exports")
	if err != nil {
		return value, err
	}
	defer stage.Close()
	destination, err := files.exports.Open("exports")
	if err != nil {
		return value, err
	}
	defer destination.Close()
	if err = sameDirectoryVolume(stage, destination); err != nil {
		return value, err
	}
	inspection := &directoryInspection{files: files, destination: destination, safe: map[string]bool{}, volumes: map[string]bool{}}
	expected := map[string]string{}
	for _, plan := range plans {
		for _, entry := range plan.Files {
			observed, err := inspection.inspectFile(ctx, entry)
			if err != nil {
				return value, err
			}
			observed.ComicID, observed.Target = plan.ComicID, exportdomain.DirectoryOutputPath(plan.Name, entry.Target)
			value.Files = append(value.Files, observed)
			if !observed.Missing {
				expected[entry.Source] = plan.ComicID
				value.ObservedBytes += observed.Size
				if value.ObservedBytes > 100<<30 {
					return value, exportdomain.ErrValidation
				}
			}
		}
	}
	visited := 0
	sources := make([]string, 0, len(expected))
	for source := range expected {
		sources = append(sources, source)
	}
	sort.Strings(sources)
	for _, plan := range plans {
		for _, directory := range plan.OwnedDirectories {
			if err = files.inspectOwnedDirectory(ctx, directory, plan.ComicID, expected, sources, &visited, 0); err != nil {
				return value, err
			}
		}
	}
	return value, nil
}

// safeParents 按父目录缓存，文件检查只验证一次路径，不逐文件重复遍历祖先。
func (inspection *directoryInspection) safeParents(name string) error {
	parent := path.Dir(name)
	if inspection.safe[parent] {
		return nil
	}
	if err := inspection.files.safeParents(parent + "/placeholder"); err != nil {
		return err
	}
	inspection.safe[parent] = true
	return nil
}

func (inspection *directoryInspection) inspectFile(ctx context.Context, entry exportdomain.DirectoryFile) (value exportdomain.DirectoryObservation, err error) {
	value.Source = entry.Source
	if err = ctx.Err(); err != nil {
		return value, err
	}
	if err = inspection.safeParents(entry.Source); err != nil {
		if entry.Optional && errors.Is(err, os.ErrNotExist) {
			value.Missing = true
			return value, nil
		}
		return value, err
	}
	before, err := inspection.files.root.Lstat(entry.Source)
	if errors.Is(err, os.ErrNotExist) && entry.Optional {
		value.Missing = true
		return value, nil
	}
	if err != nil {
		return value, err
	}
	if !before.Mode().IsRegular() || before.Size() > 20<<30 || entry.Size >= 0 && before.Size() != entry.Size {
		return value, exportdomain.ErrSource
	}
	input, err := inspection.files.root.Open(entry.Source)
	if err != nil {
		return value, exportdomain.ErrSource
	}
	defer func() { err = errors.Join(err, input.Close()) }()
	after, err := input.Stat()
	if err != nil || !after.Mode().IsRegular() || !os.SameFile(before, after) || after.Size() != before.Size() || !after.ModTime().Equal(before.ModTime()) {
		return value, exportdomain.ErrSource
	}
	value.Identity, err = relocate.Identity(input)
	if err != nil {
		return value, err
	}
	directory := path.Dir(entry.Source)
	if !inspection.volumes[directory] {
		if err = sameDirectoryVolume(input, inspection.destination); err != nil {
			return value, err
		}
		inspection.volumes[directory] = true
	}
	value.Size, value.Modified = before.Size(), before.ModTime().UnixNano()
	current, err := inspection.files.root.Lstat(entry.Source)
	if err != nil || !os.SameFile(before, current) || current.Size() != after.Size() || !current.ModTime().Equal(after.ModTime()) {
		return value, exportdomain.ErrSource
	}
	return value, nil
}

func (files *Files) inspectOwnedDirectory(ctx context.Context, name, comic string, expected map[string]string, sources []string, visited *int, depth int) error {
	if depth > 64 {
		return exportdomain.ErrValidation
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := files.safeParents(name + "/placeholder"); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			index := sort.SearchStrings(sources, name+"/")
			if index < len(sources) && directoryWithin(sources[index], name) {
				return exportdomain.ErrSource
			}
			return nil
		}
		return err
	}
	directory, err := files.root.Open(name)
	if err != nil {
		return err
	}
	defer directory.Close()
	for {
		if err = ctx.Err(); err != nil {
			return err
		}
		entries, readErr := directory.ReadDir(128)
		for _, entry := range entries {
			*visited = *visited + 1
			if *visited > exportdomain.MaxDirectoryFiles+20000 {
				return exportdomain.ErrValidation
			}
			child := name + "/" + entry.Name()
			info, err := entry.Info()
			if err != nil {
				return err
			}
			if info.IsDir() {
				if err = files.inspectOwnedDirectory(ctx, child, comic, expected, sources, visited, depth+1); err != nil {
					return err
				}
			} else if !info.Mode().IsRegular() || expected[child] != comic {
				return exportdomain.ErrConflict
			}
		}
		if errors.Is(readErr, io.EOF) {
			return nil
		}
		if readErr != nil {
			return readErr
		}
	}
}

// 同一独占根内的实际目录项按字节匹配，不把未登记文件或大小写别名当作已有媒体。
func directoryWithin(name, root string) bool {
	return name == root || strings.HasPrefix(name, root+"/")
}
