package domain

type Reader struct {
	Quality   string `json:"quality"`
	Fit       string `json:"fit"`
	Direction string `json:"direction"`
	Version   int    `json:"version"`
}
