package accounts

import (
	"bytes"
	"encoding/json"
	"slices"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"
)

var (
	validRegions   = []string{"us", "uk", "de", "fr", "it", "es", "ca", "au", "in", "nl"}
	validLanguages = []string{"en", "nl", "es"}
	validFormats   = []string{"", "m4b", "mp3"}
	validProviders = []string{"torbox", "realdebrid"}
	validModes     = []string{"auto", "debrid", "p2p"}
)

// Nullable distinguishes an absent JSON field (Set false) from null (Set true, Value nil).
type Nullable[T any] struct {
	Set   bool
	Value *T
}

// UnmarshalJSON implements json.Unmarshaler; it only runs when the field is present.
func (n *Nullable[T]) UnmarshalJSON(b []byte) error {
	n.Set = true
	if bytes.Equal(bytes.TrimSpace(b), []byte("null")) {
		n.Value = nil
		return nil
	}
	var v T
	if err := json.Unmarshal(b, &v); err != nil {
		return err
	}
	n.Value = &v
	return nil
}

// SettingsPatch is a partial settings update; nil/unset fields stay as they are.
type SettingsPatch struct {
	Region            *string          `json:"region"`
	Languages         *[]string        `json:"languages"`
	PreferFormat      *string          `json:"prefer_format"`
	PreferredProvider Nullable[string] `json:"preferred_provider"`
	SourceMode        *string          `json:"source_mode"`
	P2PEnabled        *bool            `json:"p2p_enabled"`
}

// Apply validates the patch and returns the merged settings.
func (p SettingsPatch) Apply(s Settings) (Settings, error) {
	out := s
	out.Languages = slices.Clone(s.Languages)
	if p.Region != nil {
		if !slices.Contains(validRegions, *p.Region) {
			return Settings{}, &ValidationError{"unknown region"}
		}
		out.Region = *p.Region
	}
	if p.Languages != nil {
		langs := make([]string, 0, len(*p.Languages))
		for _, l := range *p.Languages {
			if !slices.Contains(validLanguages, l) {
				return Settings{}, &ValidationError{"unknown language"}
			}
			if !slices.Contains(langs, l) {
				langs = append(langs, l)
			}
		}
		if len(langs) == 0 {
			return Settings{}, &ValidationError{"languages must not be empty"}
		}
		out.Languages = langs
	}
	if p.PreferFormat != nil {
		if !slices.Contains(validFormats, *p.PreferFormat) {
			return Settings{}, &ValidationError{"unknown prefer_format"}
		}
		out.PreferFormat = *p.PreferFormat
	}
	if p.PreferredProvider.Set {
		if v := p.PreferredProvider.Value; v != nil {
			if !slices.Contains(validProviders, *v) {
				return Settings{}, &ValidationError{"unknown preferred_provider"}
			}
			out.PreferredProvider = ptr(*v)
		} else {
			out.PreferredProvider = nil
		}
	}
	if p.SourceMode != nil {
		if !slices.Contains(validModes, *p.SourceMode) {
			return Settings{}, &ValidationError{"unknown source_mode"}
		}
		out.SourceMode = *p.SourceMode
	}
	if p.P2PEnabled != nil {
		out.P2PEnabled = *p.P2PEnabled
	}
	return out, nil
}

func ptr[T any](v T) *T { return &v }

// cleanName trims s and checks it has 1..max characters and no control characters.
func cleanName(s, what string, max int) (string, error) {
	s = strings.TrimSpace(s)
	if n := utf8.RuneCountInString(s); n < 1 || n > max {
		return "", &ValidationError{what + " must be 1-" + strconv.Itoa(max) + " characters"}
	}
	if !utf8.ValidString(s) || strings.IndexFunc(s, unicode.IsControl) >= 0 {
		return "", &ValidationError{what + " contains invalid characters"}
	}
	return s, nil
}
