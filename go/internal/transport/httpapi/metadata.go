package httpapi

import (
	"net/http"
	"strconv"
	"strings"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	settingsdomain "comicatlas.local/backend/internal/modules/settings/domain"
	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
)

func routeMethod(response http.ResponseWriter, request *http.Request, allowed ...string) bool {
	for _, method := range allowed {
		if request.Method == method {
			return true
		}
	}
	response.Header().Set("Allow", strings.Join(allowed, ", "))
	fail(response, 405, "METHOD_NOT_ALLOWED", "请求方法不受支持")
	return false
}
func sendResult(response http.ResponseWriter, value any, err error) int {
	if err != nil {
		return applicationError(response, err)
	}
	writeData(response, value)
	return 200
}

func (application *Application) serveMetadata(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	ctx := request.Context()
	if len(resource) == 3 && resource[0] == "comics" && resource[1] == "metadata" && resource[2] == "batch" {
		if !routeMethod(response, request, "PUT") {
			return 405, true
		}
		var body httprequest.MetadataBatch
		if !decodeBodyLimit(response, request, &body, 1<<20) {
			return 400, true
		}
		result, err := application.Library.UpdateMetadataBatch(ctx, body.Items)
		return sendResult(response, result, err), true
	}
	if len(resource) < 1 || (resource[0] != "categories" && resource[0] != "tags") {
		return 0, false
	}
	kind := resource[0]
	if len(resource) == 1 {
		if !routeMethod(response, request, "GET", "POST") {
			return 405, true
		}
		if request.Method == "GET" {
			page, size, err := pagination(request)
			if err != nil {
				return fail(response, 400, "VALIDATION_ERROR", "分页参数无效"), true
			}
			result, err := application.Library.Terms(ctx, kind, page, size, request.URL.Query().Get("keyword"))
			return sendResult(response, result, err), true
		}
		var body httprequest.TermCreate
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		result, err := application.Library.SaveTerm(ctx, kind, "", body.Name, 0)
		if err != nil {
			return applicationError(response, err), true
		}
		response.Header().Set("Location", "/api/v1/"+kind+"/"+result.ID)
		writeJSON(response, 201, struct {
			Data librarydomain.Term `json:"data"`
		}{result})
		return 201, true
	}
	if len(resource) == 2 {
		if !routeMethod(response, request, "GET", "PUT", "DELETE") {
			return 405, true
		}
		if request.Method == "GET" {
			result, err := application.Library.Term(ctx, kind, resource[1])
			return sendResult(response, result, err), true
		}
		if request.Method == "DELETE" {
			version, err := strconv.Atoi(request.URL.Query().Get("version"))
			if err != nil {
				return fail(response, 400, "VALIDATION_ERROR", "必须提供词条版本"), true
			}
			if err = application.Library.DeleteTerm(ctx, kind, resource[1], version); err != nil {
				return applicationError(response, err), true
			}
			response.WriteHeader(204)
			return 204, true
		}
		var body httprequest.TermUpdate
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		result, err := application.Library.SaveTerm(ctx, kind, resource[1], body.Name, body.Version)
		return sendResult(response, result, err), true
	}
	return 0, false
}

func (application *Application) serveReading(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	ctx := request.Context()
	if application.Settings != nil && len(resource) == 2 && resource[0] == "settings" && resource[1] == "reader" {
		if !routeMethod(response, request, "GET", "PUT") {
			return 405, true
		}
		if request.Method == "GET" {
			result, err := application.Settings.Reader(ctx)
			return sendResult(response, result, err), true
		}
		var body settingsdomain.Reader
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		result, err := application.Settings.SaveReader(ctx, body)
		return sendResult(response, result, err), true
	}
	if application.Reading == nil {
		return 0, false
	}
	if len(resource) == 1 && (resource[0] == "history" || resource[0] == "reactions" || resource[0] == "favorites") {
		if !routeMethod(response, request, "GET") {
			return 405, true
		}
		page, size, err := pagination(request)
		if err != nil {
			return fail(response, 400, "VALIDATION_ERROR", "分页参数无效"), true
		}
		if resource[0] == "history" {
			result, err := application.Reading.History(ctx, page, size)
			return sendResult(response, result, err), true
		}
		parameters := request.URL.Query()
		oldest := false
		if value := parameters.Get("oldest"); value != "" {
			oldest, err = strconv.ParseBool(value)
			if err != nil {
				return fail(response, 400, "VALIDATION_ERROR", "oldest 参数无效"), true
			}
		}
		value := parameters.Get("value")
		if resource[0] == "favorites" {
			if value != "" && value != "like" {
				return fail(response, 400, "VALIDATION_ERROR", "收藏仅查询喜欢标记"), true
			}
			value = "like"
		}
		result, err := application.Reading.Reactions(ctx, parameters.Get("targetType"), value, page, size, oldest)
		return sendResult(response, result, err), true
	}
	if len(resource) == 2 && resource[0] == "reactions" && resource[1] == "batch" {
		if !routeMethod(response, request, "PUT") {
			return 405, true
		}
		var body httprequest.ReactionBatch
		if !decodeBodyLimit(response, request, &body, 128<<10) {
			return 400, true
		}
		result, err := application.Reading.SetReactions(ctx, body.Items)
		return sendResult(response, result, err), true
	}
	if len(resource) == 3 && resource[2] == "reaction" {
		kind := ""
		switch resource[0] {
		case "comics":
			kind = "comic"
		case "chapters":
			kind = "chapter"
		case "media":
			kind = "media"
		default:
			return 0, false
		}
		if !routeMethod(response, request, "GET", "PUT") {
			return 405, true
		}
		if request.Method == "GET" {
			result, err := application.Reading.Reaction(ctx, kind, resource[1])
			return sendResult(response, result, err), true
		}
		var body httprequest.ReactionUpdate
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		result, err := application.Reading.SetReaction(ctx, kind, resource[1], body.Value)
		return sendResult(response, result, err), true
	}
	return 0, false
}
