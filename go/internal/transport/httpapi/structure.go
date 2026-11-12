package httpapi

import (
	"encoding/json"
	"net/http"

	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	httprequest "comicatlas.local/backend/internal/transport/httpapi/request"
)

func (application *Application) serveStructure(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	ctx := request.Context()
	if len(resource) == 4 && resource[0] == "chapters" && resource[2] == "media" && resource[3] == "order" {
		if !routeMethod(response, request, "PUT") {
			return 405, true
		}
		var body httprequest.MediaOrder
		if !decodeBodyLimit(response, request, &body, 1<<20) {
			return 400, true
		}
		result, err := application.Library.ReorderMedia(ctx, resource[1], body.Version, body.IDs)
		return sendResult(response, result, err), true
	}
	if len(resource) < 3 || resource[0] != "comics" {
		return 0, false
	}
	comicID := resource[1]
	if resource[2] == "structure" {
		if len(resource) == 4 && resource[3] == "move" {
			if !routeMethod(response, request, "PUT") {
				return 405, true
			}
			if request.URL.RawQuery != "" {
				return fail(response, 400, "VALIDATION_ERROR", "不接受查询参数"), true
			}
			var body httprequest.StructureMove
			if !decodeBodyLimit(response, request, &body, 2<<20) {
				return 400, true
			}
			var parent *string
			if len(body.ParentID) == 0 || json.Unmarshal(body.ParentID, &parent) != nil {
				return fail(response, 400, "VALIDATION_ERROR", "必须指定目标目录，null 表示根目录"), true
			}
			result, err := application.Library.MoveStructure(ctx, comicID, librarydomain.StructureMove{Version: body.Version, ParentID: parent, Items: body.Items})
			return sendResult(response, result, err), true
		}
		if len(resource) == 3 {
			if !routeMethod(response, request, "GET") {
				return 405, true
			}
			result, err := application.Library.Structure(ctx, comicID)
			return sendResult(response, result, err), true
		}
		if len(resource) == 4 && resource[3] == "order" {
			if !routeMethod(response, request, "PUT") {
				return 405, true
			}
			var body httprequest.StructureOrder
			if !decodeBodyLimit(response, request, &body, 2<<20) {
				return 400, true
			}
			result, err := application.Library.ReorderStructure(ctx, comicID, body.Version, body.ParentID, body.Items)
			return sendResult(response, result, err), true
		}
	}
	kind := ""
	switch resource[2] {
	case "catalogs":
		kind = "catalog"
	case "chapters":
		kind = "chapter"
	default:
		return 0, false
	}
	if len(resource) == 3 {
		if !routeMethod(response, request, "POST") {
			return 405, true
		}
		var body librarydomain.StructureChange
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		result, err := application.Library.CreateNode(ctx, comicID, kind, body)
		if err != nil {
			return applicationError(response, err), true
		}
		response.Header().Set("Location", "/api/v1/comics/"+comicID+"/"+resource[2]+"/"+result.Node.ID)
		writeJSON(response, 201, struct {
			Data librarydomain.StructureResult `json:"data"`
		}{result})
		return 201, true
	}
	if len(resource) == 4 {
		allowed := []string{"GET", "PUT"}
		if kind == "catalog" {
			allowed = append(allowed, "DELETE")
		}
		if !routeMethod(response, request, allowed...) {
			return 405, true
		}
		reference := librarydomain.NodeRef{Type: kind, ID: resource[3]}
		if request.Method == "GET" {
			result, err := application.Library.StructureNode(ctx, comicID, reference)
			return sendResult(response, result, err), true
		}
		if request.Method == "DELETE" {
			var body httprequest.CatalogDelete
			if !decodeBody(response, request, &body) {
				return 400, true
			}
			var reparentTo *string
			if len(body.ReparentTo) == 0 || json.Unmarshal(body.ReparentTo, &reparentTo) != nil {
				return fail(response, 400, "VALIDATION_ERROR", "必须指定子节点接收目录，null 表示根目录"), true
			}
			result, err := application.Library.DeleteCatalog(ctx, comicID, reference.ID, body.Version, reparentTo)
			return sendResult(response, result, err), true
		}
		var body librarydomain.StructureChange
		if !decodeBody(response, request, &body) {
			return 400, true
		}
		result, err := application.Library.UpdateNode(ctx, comicID, reference, body)
		return sendResult(response, result, err), true
	}
	return 0, false
}
