package domain

import (
	"encoding/xml"
)

type ComicInfo struct {
	XMLName xml.Name `xml:"ComicInfo"`
	Title   string   `xml:"Title"`
	Series  string   `xml:"Series"`
	Number  string   `xml:"Number"`
	Writer  string   `xml:"Writer"`
	Summary string   `xml:"Summary"`
	Genre   string   `xml:"Genre"`
	Tags    string   `xml:"Tags"`
}
