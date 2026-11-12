package httpapi

import (
	readingdomain "comicatlas.local/backend/internal/modules/reading/domain"
	"net/http"
	"net/url"
	"strconv"

	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
)

func (application *Application) serveReactionManagement(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	list := len(resource) == 2 && resource[0] == "management" && resource[1] == "reactions"
	batch := len(resource) == 3 && resource[0] == "management" && resource[1] == "reactions" && resource[2] == "batch"
	if !list && !batch {
		return 0, false
	}
	method := "GET"
	if batch {
		method = "PUT"
	}
	if !routeMethod(response, request, method) {
		return 405, true
	}
	if application.Reading == nil {
		return fail(response, 503, "UNAVAILABLE", "评价管理暂不可用"), true
	}
	if batch {
		if request.URL.RawQuery != "" {
			return fail(response, 400, "VALIDATION_ERROR", "批量评价不接受查询参数"), true
		}
		var body httprequest.ReactionBatch
		if !decodeBodyLimit(response, request, &body, 128<<10) {
			return 400, true
		}
		result, err := application.Reading.SetManagedReactions(request.Context(), body.Items)
		return sendResult(response, result, err), true
	}
	query, ok := managementReactionQuery(response, request)
	if !ok {
		return 400, true
	}
	result, err := application.Reading.ManagedReactions(request.Context(), query)
	return sendResult(response, result, err), true
}

func managementReactionQuery(response http.ResponseWriter, request *http.Request) (readingdomain.ManagementQuery, bool) {
	query := readingdomain.ManagementQuery{}
	parameters, err := url.ParseQuery(request.URL.RawQuery)
	if err != nil {
		fail(response, 400, "VALIDATION_ERROR", "评价查询参数无效")
		return query, false
	}
	allowed := map[string]bool{"targetType": true, "value": true, "mediaType": true, "includeTrashed": true, "oldest": true, "page": true, "pageSize": true}
	for name, values := range parameters {
		if !allowed[name] || len(values) != 1 {
			fail(response, 400, "VALIDATION_ERROR", "评价查询包含未知或重复参数")
			return query, false
		}
	}
	query.Page, query.PageSize, err = pagination(request)
	if err != nil {
		fail(response, 400, "VALIDATION_ERROR", "分页参数无效")
		return query, false
	}
	query.TargetType, query.Value, query.MediaType = parameters.Get("targetType"), parameters.Get("value"), parameters.Get("mediaType")
	for name, target := range map[string]*bool{"includeTrashed": &query.IncludeTrashed, "oldest": &query.Oldest} {
		if values, exists := parameters[name]; exists {
			*target, err = strconv.ParseBool(values[0])
			if err != nil {
				fail(response, 400, "VALIDATION_ERROR", "评价查询开关无效")
				return query, false
			}
		}
	}
	return query, true
}
