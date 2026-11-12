package ffmpeg

type report struct {
	Streams []stream `json:"streams"`
	Format  format   `json:"format"`
}

type format struct {
	Name     string `json:"format_name"`
	Duration string `json:"duration"`
}

type stream struct {
	Type        string      `json:"codec_type"`
	Codec       string      `json:"codec_name"`
	PixelFormat string      `json:"pix_fmt"`
	Width       int         `json:"width"`
	Height      int         `json:"height"`
	Duration    string      `json:"duration"`
	Disposition disposition `json:"disposition"`
}

type disposition struct {
	AttachedPicture int `json:"attached_pic"`
}
