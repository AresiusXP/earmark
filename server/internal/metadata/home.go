package metadata

import (
	"context"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/AresiusXP/earmark/server/internal/model"
)

// Sort orders of the Audible catalog used by the home shelves.
const (
	SortBestSellers = "BestSellers"
	SortNewest      = "-ReleaseDate"
)

// Genre keys of the home shelves. Audible names the root categories per marketplace and language
// (and the ids differ per marketplace), so each key lists name fragments that identify the category.
var genreNames = map[string][]string{
	"thriller":  {"thriller", "policíaca", "krimi"},
	"scifi":     {"science fiction", "ciencia ficción"},
	"romance":   {"romance", "romántica", "liebesroman"},
	"biography": {"biograph", "biografí", "biografien"},
	"history":   {"history", "historia", "geschichte"},
	"business":  {"business", "negocios", "wirtschaft"},
}

// GenreKeys lists the genre shelf keys in display order.
func GenreKeys() []string {
	return []string{"thriller", "scifi", "romance", "biography", "history", "business"}
}

const categoriesTTL = 24 * time.Hour

type category struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

type categoryCache struct {
	mu sync.Mutex
	m  map[string]cachedCategories
}

type cachedCategories struct {
	cats []category
	at   time.Time
}

// rootCategories returns the Genres root categories of a marketplace, cached for a day.
func (c *Client) rootCategories(ctx context.Context, region string) ([]category, error) {
	base, r, err := c.audibleURL(region)
	if err != nil {
		return nil, err
	}
	c.cats.mu.Lock()
	if e, ok := c.cats.m[r]; ok && c.clock().Sub(e.at) < categoriesTTL {
		c.cats.mu.Unlock()
		return e.cats, nil
	}
	c.cats.mu.Unlock()

	var out struct {
		Categories []category `json:"categories"`
	}
	v := url.Values{"categories_num_levels": {"1"}, "root": {"Genres"}}
	if err := c.getJSON(ctx, "audible", base+"/1.0/catalog/categories?"+v.Encode(), &out); err != nil {
		return nil, err
	}
	c.cats.mu.Lock()
	if c.cats.m == nil {
		c.cats.m = map[string]cachedCategories{}
	}
	c.cats.m[r] = cachedCategories{cats: out.Categories, at: c.clock()}
	c.cats.mu.Unlock()
	return out.Categories, nil
}

// categoryFor finds the id of the root category that matches the genre key ("" when none does).
func categoryFor(cats []category, genre string) string {
	for _, c := range cats {
		name := strings.ToLower(c.Name)
		for _, frag := range genreNames[genre] {
			if strings.Contains(name, frag) {
				return c.ID
			}
		}
	}
	return ""
}

// GenreBooks lists books of a home genre (see GenreKeys) in the order sort (SortBestSellers or
// SortNewest) on every marketplace that carries language. A marketplace without a matching
// category contributes nothing; an empty result is not an error.
func (c *Client) GenreBooks(ctx context.Context, region, language, genre, sort string) ([]model.Book, error) {
	if _, ok := genreNames[genre]; !ok {
		return []model.Book{}, nil
	}
	return c.perRegion(ctx, region, language, func(ctx context.Context, rg string) ([]model.Book, error) {
		cats, err := c.rootCategories(ctx, rg)
		if err != nil {
			return nil, err
		}
		id := categoryFor(cats, genre)
		if id == "" {
			return nil, nil
		}
		v := listingValues(language, sort)
		v.Set("category_id", id)
		return c.cleanListing(ctx, rg, language, v)
	})
}

// NewBooks lists the latest releases.
func (c *Client) NewBooks(ctx context.Context, region, language string) ([]model.Book, error) {
	return c.perRegion(ctx, region, language, func(ctx context.Context, rg string) ([]model.Book, error) {
		return c.cleanListing(ctx, rg, language, listingValues(language, SortNewest))
	})
}

// AuthorBooks lists the best-selling books of an author. Audible's author filter is a text search,
// so the result is narrowed to books that really list the author.
func (c *Client) AuthorBooks(ctx context.Context, region, language, author string) ([]model.Book, error) {
	author = strings.TrimSpace(author)
	if author == "" {
		return []model.Book{}, nil
	}
	books, err := c.perRegion(ctx, region, language, func(ctx context.Context, rg string) ([]model.Book, error) {
		v := url.Values{}
		v.Set("num_results", strconv.Itoa(maxResults))
		v.Set("products_sort_by", SortBestSellers)
		v.Set("author", author)
		return c.cleanListing(ctx, rg, language, v)
	})
	if err != nil {
		return nil, err
	}
	return slices.DeleteFunc(books, func(b model.Book) bool {
		return !slices.ContainsFunc(b.Authors, func(a string) bool { return strings.EqualFold(a, author) })
	}), nil
}

// SeriesBooks lists the books of a series (optionally by an author), narrowed to books whose
// series title is the given one. Books are not sorted.
func (c *Client) SeriesBooks(ctx context.Context, region, language, series, author string) ([]model.Book, error) {
	series = strings.TrimSpace(series)
	if series == "" {
		return []model.Book{}, nil
	}
	books, err := c.perRegion(ctx, region, language, func(ctx context.Context, rg string) ([]model.Book, error) {
		v := url.Values{}
		v.Set("num_results", strconv.Itoa(maxResults))
		v.Set("products_sort_by", "Relevance")
		v.Set("keywords", series)
		if a := strings.TrimSpace(author); a != "" {
			v.Set("author", a)
		}
		return c.cleanListing(ctx, rg, language, v)
	})
	if err != nil {
		return nil, err
	}
	return slices.DeleteFunc(books, func(b model.Book) bool { return !strings.EqualFold(b.Series, series) }), nil
}

func (c *Client) cleanListing(ctx context.Context, region, language string, v url.Values) ([]model.Book, error) {
	ps, r, err := c.listProducts(ctx, region, v)
	if err != nil {
		return nil, err
	}
	return cleanBooks(ps, r, language), nil
}

// listingValues are the query values of a plain listing, biased to language the way Discover is.
func listingValues(language, sort string) url.Values {
	v := url.Values{}
	v.Set("num_results", strconv.Itoa(maxResults))
	v.Set("products_sort_by", sort)
	if seed := languageSeeds[language]; seed != "" {
		v.Set("keywords", seed)
	}
	return v
}

// perRegion runs fetch on every marketplace that carries language and merges the results; one
// marketplace failing is fine while another answered. The result is never nil.
func (c *Client) perRegion(ctx context.Context, region, language string, fetch func(ctx context.Context, region string) ([]model.Book, error)) ([]model.Book, error) {
	regions := searchRegions(region, language)
	lists := make([][]model.Book, len(regions))
	errs := make([]error, len(regions))
	var wg sync.WaitGroup
	for i, rg := range regions {
		wg.Add(1)
		go func() {
			defer wg.Done()
			lists[i], errs[i] = fetch(ctx, rg)
		}()
	}
	wg.Wait()
	failed := 0
	for _, err := range errs {
		if err != nil {
			failed++
		}
	}
	if failed == len(regions) {
		return nil, errs[0]
	}
	return mergeBooks(lists), nil
}
