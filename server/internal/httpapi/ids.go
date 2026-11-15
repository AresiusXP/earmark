package httpapi

import (
	"strconv"
	"strings"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/service"
)

const idPrefix = "abk:"

func isASIN(s string) bool {
	if s == "" || len(s) > 40 {
		return false
	}
	for _, c := range s {
		switch {
		case c >= '0' && c <= '9', c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c == '_', c == '-':
		default:
			return false
		}
	}
	return true
}

func isInfoHash(s string) bool {
	if len(s) != 40 {
		return false
	}
	for _, c := range s {
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return false
		}
	}
	return true
}

// parseIndex accepts "-1" (AutoFile) and canonical non-negative decimals up to 6 digits.
func parseIndex(s string) (int, bool) {
	if s == "-1" {
		return service.AutoFile, true
	}
	if s == "" || len(s) > 6 || (len(s) > 1 && s[0] == '0') {
		return 0, false
	}
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, false
		}
	}
	n, err := strconv.Atoi(s)
	return n, err == nil
}

// parseBookID parses "abk:<ASIN>" (the ".json" suffix is already stripped).
func parseBookID(id string) (string, bool) {
	rest, ok := strings.CutPrefix(id, idPrefix)
	if !ok || !isASIN(rest) {
		return "", false
	}
	return rest, true
}

// parseVideoID parses "abk:<ASIN>:<40 hex>:<idx>" (the ".json" suffix is already stripped).
func parseVideoID(id string) (service.VideoRef, bool) {
	parts := strings.Split(id, ":")
	if len(parts) != 4 || parts[0] != "abk" || !isASIN(parts[1]) || !isInfoHash(parts[2]) {
		return service.VideoRef{}, false
	}
	idx, ok := parseIndex(parts[3])
	if !ok {
		return service.VideoRef{}, false
	}
	return service.VideoRef{ASIN: parts[1], InfoHash: parts[2], FileIndex: idx}, true
}

// isProviderName: short lowercase names such as "torbox", "realdebrid".
func isProviderName(s string) bool {
	if s == "" || len(s) > 32 {
		return false
	}
	for _, c := range s {
		if (c < 'a' || c > 'z') && (c < '0' || c > '9') {
			return false
		}
	}
	return true
}

func providerShort(p string) string {
	switch p {
	case "torbox":
		return "TB"
	case "realdebrid":
		return "RD"
	}
	if len(p) > 3 {
		p = p[:3]
	}
	return strings.ToUpper(p)
}

func providerLabel(p string) string {
	switch p {
	case "torbox":
		return "TorBox"
	case "realdebrid":
		return "Real-Debrid"
	}
	return p
}
