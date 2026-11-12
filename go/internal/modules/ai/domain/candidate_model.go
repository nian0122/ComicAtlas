package domain

type TagCandidate struct {
	Name       string  `json:"name"`
	ExistingID *string `json:"existingId"`
	Evidence   []int   `json:"evidence"`
}

// Candidate 是待人工确认的资料，不是可直接执行的元数据补丁。
type Candidate struct {
	Title       *string        `json:"title"`
	Author      *string        `json:"author"`
	Description *string        `json:"description"`
	CategoryID  *string        `json:"categoryId"`
	Tags        []TagCandidate `json:"tags"`
	Warnings    []string       `json:"warnings"`
}
