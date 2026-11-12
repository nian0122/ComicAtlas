package domain

type Video struct {
	Container    string   `json:"container"`
	VideoCodec   string   `json:"videoCodec"`
	AudioCodecs  []string `json:"audioCodecs"`
	PixelFormat  string   `json:"pixelFormat"`
	Width        int      `json:"width"`
	Height       int      `json:"height"`
	DurationMS   *int64   `json:"durationMs"`
	PlaybackHint string   `json:"playbackHint"`
}

type Probe struct {
	Extension string
	Container string
	Duration  string
	Streams   []Stream
}

type Stream struct {
	Type            string
	Codec           string
	PixelFormat     string
	Width           int
	Height          int
	Duration        string
	AttachedPicture bool
}
