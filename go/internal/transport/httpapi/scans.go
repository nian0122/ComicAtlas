package httpapi

import (
	jobsdomain "comicatlas.local/backend/internal/modules/jobs/domain"
	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
	"net/http"
)

func (application *Application) serveScans(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	batch := len(resource) == 2 && resource[0] == "import-batches"
	create := len(resource) == 1 && resource[0] == "directory-scans"
	results := len(resource) == 3 && resource[0] == "directory-scans" && resource[2] == "candidates"
	imports := len(resource) == 3 && resource[0] == "directory-scans" && resource[2] == "imports"
	archives := len(resource) == 3 && resource[0] == "imports" && resource[1] == "archives" && resource[2] == "batch"
	if !batch && !create && !results && !imports && !archives {
		return 0, false
	}
	method := "GET"
	if create || imports || archives {
		method = "POST"
	}
	if request.Method != method {
		response.Header().Set("Allow", method)
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if batch {
		value, err := application.Jobs.Store.ImportBatch(request.Context(), resource[1])
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	if application.Scans == nil {
		return fail(response, 503, "UNAVAILABLE", "导入扫描暂不可用"), true
	}
	if archives {
		var body httprequest.ArchiveBatchImport
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		value, err := application.Scans.ImportArchives(request.Context(), body.SourcePaths, request.Header.Get("Idempotency-Key"))
		if err != nil {
			return applicationError(response, err), true
		}
		application.Jobs.Wake()
		response.Header().Set("Location", "/api/v1/import-batches/"+value.ID)
		writeJSON(response, 202, struct {
			Data jobsdomain.TaskBatch `json:"data"`
		}{value})
		return 202, true
	}
	if create {
		var body httprequest.DirectoryScan
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		task, err := application.Scans.Submit(request.Context(), body.SourcePath, request.Header.Get("Idempotency-Key"))
		if err != nil {
			return applicationError(response, err), true
		}
		application.Jobs.Wake()
		response.Header().Set("Location", "/api/v1/tasks/"+task.ID)
		writeJSON(response, 202, struct {
			Data jobsdomain.Task `json:"data"`
		}{task})
		return 202, true
	}
	if results {
		page, size, err := pagination(request)
		if err != nil {
			return fail(response, 400, "VALIDATION_ERROR", "分页参数无效"), true
		}
		value, err := application.Scans.Results(request.Context(), resource[1], page, size)
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, value)
		return 200, true
	}
	var body httprequest.DirectoryScanImport
	if !decodeBody(response, request, &body) {
		return 400, true
	}
	value, err := application.Scans.Import(request.Context(), resource[1], body.CandidateIDs, request.Header.Get("Idempotency-Key"))
	if err != nil {
		return applicationError(response, err), true
	}
	application.Jobs.Wake()
	response.Header().Set("Location", "/api/v1/import-batches/"+value.ID)
	writeJSON(response, 202, struct {
		Data jobsdomain.TaskBatch `json:"data"`
	}{value})
	return 202, true
}
