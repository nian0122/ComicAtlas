package httpapi

import (
	librarydomain "comicatlas.local/backend/internal/modules/library/domain"
	"net/http"
)

func (application *Application) serveVisualPreviews(response http.ResponseWriter, request *http.Request, resource []string) (int, bool) {
	if len(resource) != 3 || resource[0] != "comics" || resource[2] != "visual-previews" {
		return 0, false
	}
	if !routeMethod(response, request, "POST") {
		return 405, true
	}
	var body struct {
		Nodes []librarydomain.NodeRef `json:"nodes"`
	}
	if !decodeBodyLimit(response, request, &body, 32<<10) {
		return 400, true
	}
	items, err := application.Library.Previews(request.Context(), resource[1], body.Nodes)
	return sendResult(response, struct {
		Items []librarydomain.NodePreview `json:"items"`
	}{items}, err), true
}
