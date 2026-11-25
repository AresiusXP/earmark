package appapi

import (
	"testing"

	"github.com/AresiusXP/earmark/server/internal/library"
	"github.com/AresiusXP/earmark/server/internal/model"
)

func TestEntryDone(t *testing.T) {
	book := model.Book{RuntimeMin: 100} // 6,000,000 ms
	tests := []struct {
		name string
		e    library.Entry
		want bool
	}{
		{"finished", library.Entry{Book: book, Status: library.StatusFinished}, true},
		{"79 percent", library.Entry{Book: book, Status: library.StatusListening, PositionMs: 4_740_000}, false},
		{"80 percent", library.Entry{Book: book, Status: library.StatusListening, PositionMs: 4_800_000}, true},
		{"unknown runtime", library.Entry{Status: library.StatusListening, PositionMs: 9_000_000}, false},
		{"saved", library.Entry{Book: book, Status: library.StatusSaved}, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := entryDone(tc.e); got != tc.want {
				t.Errorf("entryDone = %v, want %v", got, tc.want)
			}
		})
	}
}
