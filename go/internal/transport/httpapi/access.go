package httpapi

import (
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// 阅读权限取决于监听端口，不使用客户端地址或可伪造的代理/访问模式头。
func readingAllowed(request *http.Request) bool {
	if request.URL.RawPath != "" || request.URL.EscapedPath() != request.URL.Path {
		return false
	}
	if request.RequestURI != "" && !strings.HasPrefix(request.RequestURI, "/") {
		return false
	}
	parts := strings.Split(request.URL.Path, "/")
	if len(parts) < 4 || parts[0] != "" || parts[1] != "api" || parts[2] != "v1" {
		return false
	}
	resource := parts[3:]
	if !readingQueryAllowed(resource, request.URL.RawQuery) {
		return false
	}
	method := request.Method
	if len(resource) == 1 && method == "GET" {
		switch resource[0] {
		case "comics", "categories", "tags", "history", "favorites":
			return true
		}
	}
	if len(resource) < 2 || !readingID(resource[0], resource[1]) {
		return false
	}
	if len(resource) == 2 && resource[0] == "comics" && method == "GET" {
		return true
	}
	if len(resource) != 3 {
		return false
	}
	switch resource[0] {
	case "comics":
		switch resource[2] {
		case "cover":
			return method == "GET" || method == "HEAD"
		case "structure":
			return method == "GET"
		case "reading-progress", "reaction":
			return method == "GET" || method == "PUT"
		}
	case "chapters":
		if resource[2] == "reader" {
			return method == "GET"
		}
		if resource[2] == "reaction" {
			return method == "GET" || method == "PUT"
		}
	case "media":
		if resource[2] == "content" {
			return method == "GET" || method == "HEAD"
		}
		if resource[2] == "reaction" {
			return method == "GET" || method == "PUT"
		}
	}
	return false
}
func readingID(kind, id string) bool {
	prefix := ""
	switch kind {
	case "comics":
		prefix = "comic_"
	case "chapters":
		prefix = "chapter_"
	case "media":
		prefix = "media_"
		if strings.HasPrefix(id, "upload_media_") {
			prefix = "upload_media_"
		}
	default:
		return false
	}
	// 一次性迁移保留正 int64 标识；只接受规范十进制，避免放行别名或静态管理路径。
	if len(id) > 0 && len(id) <= 19 && id[0] >= '1' && id[0] <= '9' {
		_, err := strconv.ParseInt(id, 10, 64)
		return err == nil
	}
	if !strings.HasPrefix(id, prefix) || len(id) != len(prefix)+32 {
		return false
	}
	for _, c := range id[len(prefix):] {
		if (c < 'a' || c > 'f') && (c < '0' || c > '9') {
			return false
		}
	}
	return true
}

func readingQueryAllowed(resource []string, raw string) bool {
	parameters, err := url.ParseQuery(raw)
	if err != nil {
		return false
	}
	keys := ""
	if len(resource) == 1 {
		switch resource[0] {
		case "comics":
			keys = " page pageSize keyword categoryId tagId readStatus sort order "
		case "categories", "tags":
			keys = " page pageSize keyword "
		case "history":
			keys = " page pageSize "
		case "favorites":
			keys = " page pageSize targetType value oldest "
		}
	}
	if len(resource) == 3 && resource[0] == "media" && resource[2] == "content" {
		keys = " quality "
	}
	for key, values := range parameters {
		if !strings.Contains(keys, " "+key+" ") || (len(values) != 1 && !(len(resource) == 1 && resource[0] == "comics" && key == "tagId")) {
			return false
		}
	}
	return true
}
