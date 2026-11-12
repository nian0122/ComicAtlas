package domain

import mediadomain "comicatlas.local/backend/internal/modules/media/domain"

const MaxComicTags = 100

type Comic struct {
	CoverURL      string     `json:"coverUrl"`
	OriginalTitle *string    `json:"originalTitle"`
	ID            string     `json:"id"`
	Title         string     `json:"title"`
	CreatedAt     string     `json:"createdAt"`
	Author        string     `json:"author"`
	Description   string     `json:"description"`
	Category      *Term      `json:"category"`
	CategoryName  string     `json:"categoryName"`
	Tags          []ComicTag `json:"tags"`
	Version       int        `json:"version"`
}

type Catalog struct {
	ID        string  `json:"id"`
	ParentID  *string `json:"parentId"`
	Title     string  `json:"title"`
	SortOrder int     `json:"sortOrder"`
}

type Chapter struct {
	ID           string  `json:"id"`
	CatalogID    *string `json:"catalogId"`
	Title        string  `json:"title"`
	GlobalOrder  int     `json:"globalOrder"`
	SortOrder    int     `json:"sortOrder"`
	MediaVersion int     `json:"mediaVersion"`
}

type Media struct {
	MetadataOnly   bool                `json:"-"`
	StorageState   string              `json:"storageState"`
	PreviewURL     *string             `json:"previewUrl"`
	HQState        string              `json:"hqState"`
	LQ             *mediadomain.LQInfo `json:"lq"`
	AutoContentURL string              `json:"autoContentUrl"`
	Video          *mediadomain.Video  `json:"video"`
	ID             string              `json:"id"`
	ChapterID      string              `json:"chapterId"`
	PageNumber     int                 `json:"pageNumber"`
	FileName       string              `json:"fileName"`
	RelativePath   string              `json:"-"`
	MediaType      string              `json:"mediaType"`
	ContentType    string              `json:"contentType"`
	Size           int64               `json:"size"`
	Width          *int                `json:"width"`
	Height         *int                `json:"height"`
	SHA256         string              `json:"-"`
	ContentURL     string              `json:"contentUrl"`
}

type Plan struct {
	ComicInfo *ComicInfo `json:"comicInfo"`
	Comic     Comic      `json:"comic"`
	Catalogs  []Catalog  `json:"catalogs"`
	Chapters  []Chapter  `json:"chapters"`
	Media     []Media    `json:"media"`
	Progress  *Progress  `json:"progress,omitempty"`
}

type Detail struct {
	ComicInfo *ComicInfo `json:"comicInfo"`
	Comic
	StructureVersion int       `json:"structureVersion"`
	Catalogs         []Catalog `json:"catalogs"`
	Chapters         []Chapter `json:"chapters"`
}

type Reader struct {
	ComicID           string  `json:"comicId"`
	Chapter           Chapter `json:"chapter"`
	Items             []Media `json:"items"`
	PreviousChapterID *string `json:"previousChapterId"`
	NextChapterID     *string `json:"nextChapterId"`
}

type Progress struct {
	MediaID    string `json:"mediaId"`
	ComicID    string `json:"comicId"`
	ChapterID  string `json:"chapterId"`
	PageNumber int    `json:"pageNumber"`
	UpdatedAt  string `json:"updatedAt"`
}

type Page struct {
	Items    []Comic `json:"items"`
	Page     int     `json:"page"`
	PageSize int     `json:"pageSize"`
	Total    int     `json:"total"`
}

type ListQuery struct {
	Page       int
	PageSize   int
	Keyword    string
	CategoryID string
	TagIDs     []string
	ReadStatus string
	Sort       string
	Order      string
}

type MediaOrder struct {
	Version int             `json:"version"`
	Items   []MediaPosition `json:"items"`
}

type MediaPosition struct {
	ID         string `json:"id"`
	PageNumber int    `json:"pageNumber"`
}

type Term struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Version int    `json:"version"`
}

type ComicTag struct {
	Term
	Source string `json:"source"`
}

type TermPage struct {
	Items    []Term `json:"items"`
	Page     int    `json:"page"`
	PageSize int    `json:"pageSize"`
	Total    int    `json:"total"`
}

type MetadataUpdate struct {
	OriginalTitle *string  `json:"originalTitle"`
	Version       int      `json:"version"`
	Title         string   `json:"title"`
	Author        string   `json:"author"`
	Description   string   `json:"description"`
	CategoryID    *string  `json:"categoryId"`
	TagIDs        []string `json:"tagIds"`
}

type MetadataChange struct {
	ID string `json:"id"`
	MetadataUpdate
}

type MetadataVersion struct {
	ID      string `json:"id"`
	Version int    `json:"version"`
}

type NodeRef struct {
	Type string `json:"type"`
	ID   string `json:"id"`
}

type StructureNode struct {
	NodeRef
	ParentID    *string `json:"parentId"`
	Title       string  `json:"title"`
	SortOrder   int     `json:"sortOrder"`
	GlobalOrder *int    `json:"globalOrder"`
}

type Structure struct {
	Version int             `json:"version"`
	Nodes   []StructureNode `json:"nodes"`
}

type StructureChange struct {
	Version  int     `json:"version"`
	Title    string  `json:"title"`
	ParentID *string `json:"parentId"`
}

type StructureResult struct {
	Version int            `json:"version"`
	Node    *StructureNode `json:"node"`
}

type TreeNode struct {
	StructureNode
	Original StructureNode
	Created  bool
}

type StructureTree struct {
	Version  int
	Nodes    map[string]*TreeNode
	Children map[string][]*TreeNode
	Deleted  string
}
