package service

import (
	"cmp"
	"slices"
	"sort"
	"strings"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

// scored is a candidate release together with what is known about its debrid availability.
type scored struct {
	Candidate
	// Cached[provider]: absent = unknown.
	Cached map[string]bool
}

func (s scored) cachedAnywhere() bool {
	for _, c := range s.Cached {
		if c {
			return true
		}
	}
	return false
}

// filterReleases drops releases whose parsed language is explicitly set and not wanted and,
// unless showUncached, releases that are known not to be cached at any of the providers.
// A release with an unknown state at some provider (e.g. one without a cache API) is kept,
// as is everything when no provider is configured. The input order is preserved.
func filterReleases(in []scored, languages, providers []string, showUncached bool) []scored {
	out := make([]scored, 0, len(in))
	for _, r := range in {
		if l := strings.ToLower(r.Release.Parsed.Language); l != "" && !containsFold(languages, l) {
			continue
		}
		if !showUncached && len(providers) > 0 && knownUncached(r, providers) {
			continue
		}
		out = append(out, r)
	}
	return out
}

func knownUncached(r scored, providers []string) bool {
	for _, p := range providers {
		if cached, known := r.Cached[p]; !known || cached {
			return false
		}
	}
	return true
}

func containsFold(list []string, s string) bool {
	return slices.ContainsFunc(list, func(x string) bool { return strings.EqualFold(x, s) })
}

// rankReleases orders releases best first: cached anywhere, language preference (cfg order,
// unknown language after the wanted ones), format, bitrate (unknown last), match score, own
// release before series pack, seeders.
// It returns a new slice; the order is total (info hash breaks the last tie).
func rankReleases(in []scored, languages []string, preferFormat string) []scored {
	out := slices.Clone(in)
	sort.SliceStable(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if ca, cb := a.cachedAnywhere(), b.cachedAnywhere(); ca != cb {
			return ca
		}
		if la, lb := languageRank(a.Release.Parsed.Language, languages), languageRank(b.Release.Parsed.Language, languages); la != lb {
			return la < lb
		}
		if fa, fb := formatRank(a.Release.Parsed.Format, preferFormat), formatRank(b.Release.Parsed.Format, preferFormat); fa != fb {
			return fa < fb
		}
		if ba, bb := a.Release.Parsed.BitrateKbps, b.Release.Parsed.BitrateKbps; ba != bb {
			return ba > bb // 0 (unknown) is the smallest, so it ends up last
		}
		if a.Score != b.Score {
			return a.Score > b.Score
		}
		if a.Collection != b.Collection {
			return !a.Collection // a book of its own before a pack
		}
		if a.Release.Seeders != b.Release.Seeders {
			return a.Release.Seeders > b.Release.Seeders
		}
		return a.Release.InfoHash < b.Release.InfoHash
	})
	return out
}

// languageRank: position in the wanted list, then unknown, then anything else.
func languageRank(lang string, wanted []string) int {
	if lang == "" {
		return len(wanted)
	}
	for i, w := range wanted {
		if strings.EqualFold(w, lang) {
			return i
		}
	}
	return len(wanted) + 1
}

// formatRank puts the preferred format first (default m4b), the other one of m4b/mp3 next and
// every other format last.
func formatRank(format, prefer string) int {
	order := []string{"m4b", "mp3"}
	if strings.EqualFold(prefer, "mp3") {
		order = []string{"mp3", "m4b"}
	}
	for i, f := range order {
		if strings.EqualFold(format, f) {
			return i
		}
	}
	return len(order)
}

// audioFiles keeps the audio files, in natural path order, at most max of them.
func audioFiles(files []model.File, max int) []model.File {
	out := make([]model.File, 0, len(files))
	for _, f := range files {
		if f.IsAudio() {
			out = append(out, f)
		}
	}
	sort.SliceStable(out, func(i, j int) bool {
		if c := naturalCompare(out[i].Path, out[j].Path); c != 0 {
			return c < 0
		}
		return out[i].Index < out[j].Index
	})
	if max > 0 && len(out) > max {
		out = out[:max]
	}
	return out
}

// largestAudio returns the largest audio file (ties: natural path order, then lowest index).
func largestAudio(files []model.File) (model.File, bool) {
	var best model.File
	found := false
	for _, f := range audioFiles(files, 0) {
		if !found || f.Size > best.Size {
			best, found = f, true
		}
	}
	return best, found
}

// naturalCompare compares a and b case-insensitively, treating runs of ASCII digits as
// numbers ("2" < "10", "02" == "2" up to the final tie-break). Result is <0, 0 or >0.
func naturalCompare(a, b string) int {
	la, lb := strings.ToLower(a), strings.ToLower(b)
	i, j := 0, 0
	for i < len(la) && j < len(lb) {
		ca, cb := la[i], lb[j]
		if isDigit(ca) && isDigit(cb) {
			si := i
			for i < len(la) && isDigit(la[i]) {
				i++
			}
			sj := j
			for j < len(lb) && isDigit(lb[j]) {
				j++
			}
			na := strings.TrimLeft(la[si:i], "0")
			nb := strings.TrimLeft(lb[sj:j], "0")
			if len(na) != len(nb) {
				return cmp.Compare(len(na), len(nb))
			}
			if c := strings.Compare(na, nb); c != 0 {
				return c
			}
			continue
		}
		if ca != cb {
			return cmp.Compare(int(ca), int(cb))
		}
		i++
		j++
	}
	if c := cmp.Compare(len(la)-i, len(lb)-j); c != 0 {
		return c
	}
	// Equal after folding: fewer leading zeros first, then plain order, so the order is total.
	return strings.Compare(a, b)
}

func isDigit(c byte) bool { return c >= '0' && c <= '9' }
