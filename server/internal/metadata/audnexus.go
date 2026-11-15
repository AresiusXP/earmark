package metadata

import (
	"context"
	"errors"
	"net/url"
	"strings"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

type audnexusPerson struct {
	Name string `json:"name"`
}

type audnexusSeries struct {
	Name     string `json:"name"`
	Position string `json:"position"`
}

type audnexusGenre struct {
	Name string `json:"name"`
	Type string `json:"type"`
}

type audnexusBook struct {
	ASIN          string           `json:"asin"`
	Title         string           `json:"title"`
	Subtitle      string           `json:"subtitle"`
	Authors       []audnexusPerson `json:"authors"`
	Narrators     []audnexusPerson `json:"narrators"`
	Description   string           `json:"description"`
	Summary       string           `json:"summary"`
	Image         string           `json:"image"`
	Language      string           `json:"language"`
	RuntimeMin    int              `json:"runtimeLengthMin"`
	ReleaseDate   string           `json:"releaseDate"`
	Region        string           `json:"region"`
	Genres        []audnexusGenre  `json:"genres"`
	SeriesPrimary *audnexusSeries  `json:"seriesPrimary"`
}

func (a audnexusBook) toBook(region string) model.Book {
	conv := func(in []audnexusPerson) []string {
		n := make([]audibleName, len(in))
		for i, p := range in {
			n[i] = audibleName{Name: p.Name}
		}
		return names(n)
	}
	b := model.Book{
		ASIN:        a.ASIN,
		Title:       strings.TrimSpace(a.Title),
		Subtitle:    strings.TrimSpace(a.Subtitle),
		Authors:     conv(a.Authors),
		Narrators:   conv(a.Narrators),
		Language:    NormalizeLanguage(a.Language),
		RuntimeMin:  a.RuntimeMin,
		CoverURL:    HTTPSURL(a.Image),
		Region:      region,
		ReleaseYear: yearOf(a.ReleaseDate),
		Description: firstNonEmpty(strings.TrimSpace(a.Description), stripHTML(a.Summary)),
	}
	if a.SeriesPrimary != nil && strings.TrimSpace(a.SeriesPrimary.Name) != "" {
		b.Series = strings.TrimSpace(a.SeriesPrimary.Name)
		b.SeriesPosition = strings.TrimSpace(a.SeriesPrimary.Position)
	}
	seen := map[string]bool{}
	for _, g := range a.Genres {
		if g.Type != "genre" {
			continue
		}
		if n := strings.TrimSpace(g.Name); n != "" && !seen[n] {
			seen[n] = true
			b.Genres = append(b.Genres, n)
		}
	}
	return b
}

// Book returns details for an ASIN: Audnexus first, the Audible catalog when Audnexus has
// no such item in the region. Returns ErrNotFound when neither knows it.
func (c *Client) Book(ctx context.Context, region, asin string) (model.Book, error) {
	if !ValidASIN(asin) {
		return model.Book{}, ErrInvalidASIN
	}
	r, err := MarketplaceRegion(region)
	if err != nil {
		return model.Book{}, err
	}
	var ab audnexusBook
	v := url.Values{"region": {r}}
	err = c.getJSON(ctx, "audnexus", c.audnexusURL()+"/books/"+asin+"?"+v.Encode(), &ab)
	switch {
	case err == nil:
		b := ab.toBook(r)
		if b.ASIN == "" {
			b.ASIN = asin
		}
		if b.Title != "" && len(b.Authors) > 0 {
			return b, nil
		}
	case errors.Is(err, ErrNotFound):
	default:
		return model.Book{}, err
	}
	return c.audibleBook(ctx, r, asin)
}

type audnexusChapters struct {
	Chapters []struct {
		Title    string `json:"title"`
		StartMs  int    `json:"startOffsetMs"`
		LengthMs int    `json:"lengthMs"`
	} `json:"chapters"`
}

// Chapters returns the chapter markers of an ASIN, or ErrNotFound.
func (c *Client) Chapters(ctx context.Context, region, asin string) ([]Chapter, error) {
	if !ValidASIN(asin) {
		return nil, ErrInvalidASIN
	}
	r, err := MarketplaceRegion(region)
	if err != nil {
		return nil, err
	}
	var ac audnexusChapters
	v := url.Values{"region": {r}}
	if err := c.getJSON(ctx, "audnexus", c.audnexusURL()+"/books/"+asin+"/chapters?"+v.Encode(), &ac); err != nil {
		return nil, err
	}
	out := make([]Chapter, 0, len(ac.Chapters))
	for _, ch := range ac.Chapters {
		out = append(out, Chapter{Title: ch.Title, StartMs: ch.StartMs, LengthMs: ch.LengthMs})
	}
	return out, nil
}
