package httpapi

import (
	storagedomain "comicatlas.local/backend/internal/modules/storage/domain"
	"net/http"
	"net/url"
	"strings"
)

func (application *Application) serveStorageBrowse(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if len(resource) < 2 || resource[0] != "storage" {
		return 0, false
	}
	list := len(resource) == 2 && resource[1] == "comics"
	chapters := len(resource) == 4 && resource[1] == "comics" && resource[3] == "chapters"
	object := len(resource) == 3 && (resource[1] == "comics" || resource[1] == "chapters")
	if !list && !chapters && !object {
		return 0, false
	}
	if request.Method != http.MethodGet {
		response.Header().Set("Allow", "GET")
		return fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持"), true
	}
	if application.Storage == nil {
		return fail(response, 503, "UNAVAILABLE", "存储管理暂不可用"), true
	}
	parameters, err := url.ParseQuery(request.URL.RawQuery)
	allowed := " page pageSize keyword categoryId tagId tagMode uncategorized hqStatus lqStatus attention sort order "
	if object {
		allowed = ""
	}
	if err != nil {
		return fail(response, 400, "VALIDATION_ERROR", "查询参数无效"), true
	}
	for key, values := range parameters {
		if !strings.Contains(allowed, " "+key+" ") {
			return fail(response, 400, "VALIDATION_ERROR", "查询参数无效"), true
		}
		if key == "tagId" {
			continue
		}
		if len(values) != 1 {
			return fail(response, 400, "VALIDATION_ERROR", "查询参数无效"), true
		}
	}
	uncategorized := false
	if value := parameters.Get("uncategorized"); value != "" {
		if value != "true" {
			return fail(response, 400, "VALIDATION_ERROR", "查询参数无效"), true
		}
		uncategorized = true
	}
	if object {
		kind := "comic"
		if resource[1] == "chapters" {
			kind = "chapter"
		}
		result, err := application.Storage.Object(request.Context(), kind, resource[2])
		if err != nil {
			return applicationError(response, err), true
		}
		writeData(response, result)
		return 200, true
	}
	page, size, err := pagination(request)
	if err != nil {
		return fail(response, 400, "VALIDATION_ERROR", "分页参数无效"), true
	}
	kind, parent := "comic", ""
	if chapters {
		kind, parent = "chapter", resource[2]
	}
	result, err := application.Storage.Browse(request.Context(), kind, parent, storagedomain.BrowseQuery{
		Page: page, PageSize: size, Keyword: parameters.Get("keyword"), CategoryID: parameters.Get("categoryId"),
		Uncategorized: uncategorized, TagIDs: parameters["tagId"], TagMode: parameters.Get("tagMode"),
		HQStatus: parameters.Get("hqStatus"), LQStatus: parameters.Get("lqStatus"), Attention: parameters.Get("attention"), Sort: parameters.Get("sort"), Order: parameters.Get("order"),
	})
	if err != nil {
		return applicationError(response, err), true
	}
	writeData(response, result)
	return 200, true
}
