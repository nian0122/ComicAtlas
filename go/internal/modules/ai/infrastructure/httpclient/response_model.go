package httpclient

import aidomain "comicatlas.local/backend/internal/modules/ai/domain"

type wireResponse struct {
	Version   int                `json:"version"`
	RequestID string             `json:"requestId"`
	Candidate aidomain.Candidate `json:"candidate"`
}
