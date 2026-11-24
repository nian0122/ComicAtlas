package filesystem

import (
	exportdomain "comicatlas.local/backend/internal/modules/exporting/domain"
	"context"
	"encoding/json"
	"fmt"
	"path"
	"strings"
	"time"
)

type directoryItem struct {
	entry         exportdomain.DirectoryFile
	state         exportdomain.DirectoryFileState
	comic, target string
	position      int
	location      string
}

type directoryGroup struct {
	target  string
	items   []*directoryItem
	receipt string
	phase   string
}

type directoryExecution struct {
	files        *Files
	id, base     string
	planning     exportdomain.DirectoryPlanningResult
	marker       exportdomain.DirectoryMarker
	published    bool
	groups       []*directoryGroup
	items        []*directoryItem
	controls     map[string][]byte
	receipts     map[string]*directoryGroup
	directories  map[string]bool
	safeDirs     map[string]bool
	createdDirs  map[string]bool
	wholeSources map[string]bool
	stats        exportdomain.DirectoryExecutionStats
}

// ExecuteDirectory 只消费唯一不可变计划。每次执行核对文件属性后发布。
func (files *Files) ExecuteDirectory(ctx context.Context, id string, planning exportdomain.DirectoryPlanningResult, visit func([]exportdomain.DirectoryFileResult) error) (exportdomain.DirectoryReport, error) {
	files.directoryMutex.Lock()
	defer files.directoryMutex.Unlock()
	var report exportdomain.DirectoryReport
	if err := ctx.Err(); err != nil {
		return report, err
	}
	if !directoryPublishSupported || !exportdomain.TaskID(id) || exportdomain.ValidateDirectoryPlanning(planning) != nil {
		return report, exportdomain.ErrManifest
	}
	move := newDirectoryExecution(files, id, planning)
	start := time.Now()
	if err := move.prepareOutput(ctx); err != nil {
		return report, err
	}
	if err := move.locateScene(ctx); err != nil {
		return report, err
	}
	move.stats.SceneMillis = time.Since(start).Milliseconds()
	var moveTime, persistTime time.Duration
	for _, group := range move.groups {
		start = time.Now()
		if !move.published {
			if err := move.moveGroup(ctx, group); err != nil {
				return report, err
			}
		}
		moveTime += time.Since(start)
		if visit != nil {
			start = time.Now()
			rows := make([]exportdomain.DirectoryFileResult, 0, exportdomain.MaxDirectoryResultBatch)
			for index, item := range group.items {
				rows = append(rows, item.result())
				if len(rows) == cap(rows) || index == len(group.items)-1 {
					if err := visit(rows); err != nil {
						return report, err
					}
					move.stats.ResultBatches++
					rows = rows[:0]
				}
			}
			persistTime += time.Since(start)
		}
	}
	start = time.Now()
	if err := move.finishOutput(ctx); err != nil {
		return report, err
	}
	move.stats.PublishMillis = time.Since(start).Milliseconds()
	for _, item := range move.items {
		report.Bytes += item.state.Size
	}
	report.FileCount = len(move.items)
	move.stats.MoveMillis = moveTime.Milliseconds()
	move.stats.PersistMillis = persistTime.Milliseconds()
	report.Stats = move.stats
	return report, nil
}

func newDirectoryExecution(files *Files, id string, planning exportdomain.DirectoryPlanningResult) *directoryExecution {
	move := &directoryExecution{files: files, id: id, base: "staging/exports/" + id, planning: planning, marker: exportdomain.DirectoryMarker{Version: exportdomain.DirectoryPlanVersion, TaskID: id}, controls: map[string][]byte{}, receipts: map[string]*directoryGroup{}, directories: map[string]bool{".": true}, safeDirs: map[string]bool{}, createdDirs: map[string]bool{}, wholeSources: map[string]bool{}}
	groups := map[string]*directoryGroup{}
	addDirectory := func(name string) {
		for name != "." {
			move.directories[name] = true
			name = path.Dir(name)
		}
	}
	marker, _ := json.Marshal(move.marker)
	move.controls[exportdomain.DirectoryMarkerName] = marker
	for _, plan := range planning.Plans {
		addDirectory(exportdomain.DirectoryOutputPath(plan.Name, ""))
		for _, name := range plan.Directories {
			addDirectory(exportdomain.DirectoryOutputPath(plan.Name, name))
		}
		for _, document := range plan.Documents {
			name := exportdomain.DirectoryOutputPath(plan.Name, document.Name)
			move.controls[name] = document.Data
			addDirectory(path.Dir(name))
		}
		for _, file := range plan.Files {
			position := len(move.items) + 1
			item := &directoryItem{entry: file, state: planning.Measurements[position-1], comic: plan.ComicID, target: exportdomain.DirectoryOutputPath(plan.Name, file.Target), position: position}
			move.items = append(move.items, item)
			key := path.Dir(item.target)
			group := groups[key]
			if group == nil {
				group = &directoryGroup{target: key}
				groups[key] = group
				move.groups = append(move.groups, group)
			}
			group.items = append(group.items, item)
			if !item.state.Missing {
				addDirectory(key)
			}
		}
	}
	for _, group := range move.groups {
		targets := map[string]*directoryItem{}
		for _, item := range group.items {
			if !item.state.Missing {
				targets[strings.ToLower(path.Base(item.target))] = item
			}
		}
		for _, item := range group.items {
			other := targets[strings.ToLower(path.Base(item.entry.Source))]
			if !item.state.Missing && other != nil && other != item {
				group.receipt = fmt.Sprintf(".comicatlas-names-%06d.json", group.items[0].position)
				move.receipts[group.receipt] = group
				break
			}
		}
	}
	return move
}

func (item *directoryItem) result() exportdomain.DirectoryFileResult {
	row := exportdomain.DirectoryFileResult{Position: item.position, ComicID: item.comic, Source: item.entry.Source, Target: item.target, Size: item.state.Size, Modified: item.state.Modified, State: "moved"}
	if item.state.Missing {
		row.State = "initially_missing"
	}
	return row
}
