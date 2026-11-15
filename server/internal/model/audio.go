package model

import (
	"path"
	"strings"
)

var audioExt = map[string]bool{
	".mp3": true, ".m4b": true, ".m4a": true, ".aac": true, ".flac": true,
	".ogg": true, ".opus": true, ".wma": true, ".wav": true,
}

// IsAudioPath reports whether name has an audio file extension.
func IsAudioPath(name string) bool {
	return audioExt[strings.ToLower(path.Ext(name))]
}
