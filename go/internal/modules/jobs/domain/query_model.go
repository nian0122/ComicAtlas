package domain

type Filter struct {
	Type    string
	Keyword string
}

type ListQuery struct {
	Filter
	Status   string
	Page     int
	PageSize int
	Oldest   bool
}

type TaskPage struct {
	Items    []Task `json:"items"`
	Page     int    `json:"page"`
	PageSize int    `json:"pageSize"`
	Total    int    `json:"total"`
}

type Summary struct {
	Total      int `json:"total"`
	Queued     int `json:"queued"`
	Running    int `json:"running"`
	Cancelling int `json:"cancelling"`
	Cancelled  int `json:"cancelled"`
	Succeeded  int `json:"succeeded"`
	Failed     int `json:"failed"`
}
