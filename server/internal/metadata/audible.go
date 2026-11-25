package metadata

import (
	"context"
	"net/url"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"

	"github.com/AresiusXP/earmark/server/internal/model"
)

const responseGroups = "contributors,media,product_desc,product_attrs,series,product_extended_attrs"

type audibleName struct {
	Name string `json:"name"`
}

type audibleSeries struct {
	Title    string `json:"title"`
	Sequence string `json:"sequence"`
}

type audibleProduct struct {
	ASIN              string            `json:"asin"`
	Title             string            `json:"title"`
	Subtitle          string            `json:"subtitle"`
	ContentType       string            `json:"content_type"`
	Language          string            `json:"language"`
	Authors           []audibleName     `json:"authors"`
	Narrators         []audibleName     `json:"narrators"`
	Series            []audibleSeries   `json:"series"`
	RuntimeMin        int               `json:"runtime_length_min"`
	IssueDate         string            `json:"issue_date"`
	ReleaseDate       string            `json:"release_date"`
	Images            map[string]string `json:"product_images"`
	MerchandisingSumm string            `json:"merchandising_summary"`
	PublisherSummary  string            `json:"publisher_summary"`
}

type audibleList struct {
	Products []audibleProduct `json:"products"`
}

type audibleSingle struct {
	Product audibleProduct `json:"product"`
}

// toBook maps an Audible product. region is the effective marketplace region.
func (p audibleProduct) toBook(region string) model.Book {
	b := model.Book{
		ASIN:        p.ASIN,
		Title:       strings.TrimSpace(p.Title),
		Subtitle:    strings.TrimSpace(p.Subtitle),
		Authors:     names(p.Authors),
		Narrators:   names(p.Narrators),
		Language:    NormalizeLanguage(p.Language),
		RuntimeMin:  p.RuntimeMin,
		CoverURL:    bestImage(p.Images),
		Region:      region,
		ReleaseYear: yearOf(firstNonEmpty(p.ReleaseDate, p.IssueDate)),
	}
	// A book may belong to an umbrella series without a position ("The Cosmere") besides its
	// numbered one: prefer the numbered series.
	if len(p.Series) > 0 {
		s := p.Series[0]
		for _, c := range p.Series {
			if strings.TrimSpace(c.Sequence) != "" && strings.TrimSpace(c.Title) != "" {
				s = c
				break
			}
		}
		b.Series = strings.TrimSpace(s.Title)
		if b.Series != "" {
			b.SeriesPosition = strings.TrimSpace(s.Sequence)
		}
	}
	b.Description = firstNonEmpty(stripHTML(p.MerchandisingSumm), stripHTML(p.PublisherSummary))
	return b
}

func names(in []audibleName) []string {
	out := make([]string, 0, len(in))
	seen := map[string]bool{}
	for _, n := range in {
		s := strings.TrimSpace(n.Name)
		if s == "" || seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	return out
}

// bestImage picks the largest product image (keys are pixel sizes) and forces https.
func bestImage(m map[string]string) string {
	type kv struct {
		size int
		url  string
	}
	var all []kv
	for k, v := range m {
		n, _ := strconv.Atoi(k)
		all = append(all, kv{n, v})
	}
	sort.Slice(all, func(i, j int) bool { return all[i].size > all[j].size })
	for _, e := range all {
		if u := HTTPSURL(e.url); u != "" {
			return u
		}
	}
	return ""
}

// HTTPSURL upgrades http:// and protocol-relative URLs to https and rejects other schemes.
func HTTPSURL(raw string) string {
	raw = strings.TrimSpace(raw)
	switch {
	case raw == "":
		return ""
	case strings.HasPrefix(raw, "https://"):
		return raw
	case strings.HasPrefix(raw, "http://"):
		return "https://" + strings.TrimPrefix(raw, "http://")
	case strings.HasPrefix(raw, "//"):
		return "https:" + raw
	}
	return ""
}

// NormalizeLanguage turns Audible/Audnexus language names into ISO 639-1; unknown -> "".
func NormalizeLanguage(l string) string {
	l = strings.ToLower(strings.TrimSpace(l))
	if l == "" {
		return ""
	}
	if v, ok := isoLanguages[l]; ok {
		return v
	}
	if len(l) == 2 {
		return l
	}
	return ""
}

var isoLanguages = map[string]string{
	"english": "en", "dutch": "nl", "german": "de", "french": "fr", "spanish": "es",
	"italian": "it", "portuguese": "pt", "swedish": "sv", "danish": "da", "norwegian": "no",
	"finnish": "fi", "polish": "pl", "russian": "ru", "japanese": "ja", "chinese": "zh",
	"afrikaans": "af", "icelandic": "is", "catalan": "ca", "czech": "cs", "hungarian": "hu",
	"turkish": "tr", "hindi": "hi", "korean": "ko", "greek": "el",
}

func yearOf(date string) int {
	if len(date) < 4 {
		return 0
	}
	y, err := strconv.Atoi(date[:4])
	if err != nil || y < 1000 {
		return 0
	}
	return y
}

func firstNonEmpty(ss ...string) string {
	for _, s := range ss {
		if s != "" {
			return s
		}
	}
	return ""
}

// stripHTML removes tags and collapses whitespace; entities are decoded for the common cases.
func stripHTML(s string) string {
	if s == "" {
		return ""
	}
	var b strings.Builder
	inTag := false
	for _, r := range s {
		switch {
		case r == '<':
			inTag = true
			b.WriteByte(' ')
		case r == '>' && inTag:
			inTag = false
		case !inTag:
			b.WriteRune(r)
		}
	}
	out := strings.NewReplacer("&amp;", "&", "&lt;", "<", "&gt;", ">", "&quot;", `"`, "&#39;", "'", "&nbsp;", " ").Replace(b.String())
	return strings.Join(strings.Fields(out), " ")
}

// cleanBooks keeps real audiobooks of the wanted language, drops items without
// authors or title, and dedupes by ASIN preserving order.
func cleanBooks(in []audibleProduct, region, language string) []model.Book {
	out := make([]model.Book, 0, len(in))
	seen := map[string]bool{}
	for _, p := range in {
		if !ValidASIN(p.ASIN) || seen[p.ASIN] {
			continue
		}
		if p.ContentType != "" && p.ContentType != "Product" {
			continue
		}
		b := p.toBook(region)
		if b.Title == "" || len(b.Authors) == 0 {
			continue
		}
		if language != "" && b.Language != "" && b.Language != language {
			continue
		}
		seen[p.ASIN] = true
		out = append(out, b)
	}
	return out
}

func (c *Client) listProducts(ctx context.Context, region string, v url.Values) ([]audibleProduct, string, error) {
	base, r, err := c.audibleURL(region)
	if err != nil {
		return nil, "", err
	}
	v.Set("response_groups", responseGroups)
	v.Set("image_sizes", "500")
	v.Set("content_type", "Product")
	var list audibleList
	if err := c.getJSON(ctx, "audible", base+"/1.0/catalog/products?"+v.Encode(), &list); err != nil {
		return nil, "", err
	}
	return list.Products, r, nil
}

// Search queries the Audible catalog. language ("en", "nl", "es", "" any) is applied client-side
// because the API has no language filter; items whose language is unknown are kept. For "es" the
// es marketplace is asked as well as region (see searchRegions) and the results are merged.
func (c *Client) Search(ctx context.Context, region, language, query string) ([]model.Book, error) {
	query = strings.TrimSpace(query)
	if query == "" {
		return []model.Book{}, nil
	}
	v := url.Values{}
	v.Set("keywords", query)
	v.Set("num_results", strconv.Itoa(maxResults))
	v.Set("products_sort_by", "Relevance")
	return c.listBooks(ctx, region, language, v, true)
}

// listBooks runs the catalog query v on every marketplace that carries language (just region
// unless multi) and merges the cleaned results round-robin, deduped by ASIN. One marketplace
// failing is fine as long as another one answered.
func (c *Client) listBooks(ctx context.Context, region, language string, v url.Values, multi bool) ([]model.Book, error) {
	regions := []string{region}
	if multi {
		regions = searchRegions(region, language)
	}
	if len(regions) == 1 {
		ps, r, err := c.listProducts(ctx, regions[0], v)
		if err != nil {
			return nil, err
		}
		return cleanBooks(ps, r, language), nil
	}
	lists := make([][]model.Book, len(regions))
	errs := make([]error, len(regions))
	var wg sync.WaitGroup
	for i, rg := range regions {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ps, r, err := c.listProducts(ctx, rg, cloneValues(v))
			if err != nil {
				errs[i] = err
				return
			}
			lists[i] = cleanBooks(ps, r, language)
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

func cloneValues(v url.Values) url.Values {
	out := make(url.Values, len(v))
	for k, vs := range v {
		out[k] = slices.Clone(vs)
	}
	return out
}

// mergeBooks interleaves the lists (first of each, second of each...) and drops repeated ASINs.
func mergeBooks(lists [][]model.Book) []model.Book {
	out := []model.Book{}
	seen := map[string]bool{}
	for i := 0; ; i++ {
		more := false
		for _, l := range lists {
			if i >= len(l) {
				continue
			}
			more = true
			if b := l[i]; !seen[b.ASIN] {
				seen[b.ASIN] = true
				out = append(out, b)
			}
		}
		if !more {
			return out
		}
	}
}

// Discover lists best sellers. page is 0-based. For languages the API cannot filter on
// (nl, es), a seed keyword biases the listing and results are filtered client-side. For "es" the
// es marketplace is asked as well as region.
func (c *Client) Discover(ctx context.Context, region, language string, page int) ([]model.Book, error) {
	return c.DiscoverCategory(ctx, region, language, "", page)
}

// DiscoverCategory is Discover restricted to an Audible category id (may be empty).
func (c *Client) DiscoverCategory(ctx context.Context, region, language, categoryID string, page int) ([]model.Book, error) {
	if page < 0 {
		page = 0
	}
	v := url.Values{}
	v.Set("num_results", strconv.Itoa(maxResults))
	v.Set("page", strconv.Itoa(page))
	v.Set("products_sort_by", "BestSellers")
	if seed := languageSeeds[language]; seed != "" {
		v.Set("keywords", seed)
	}
	if categoryID != "" {
		v.Set("category_id", categoryID)
	}
	// Category ids belong to one marketplace, so a category listing never fans out.
	return c.listBooks(ctx, region, language, v, categoryID == "")
}

// audibleBook fetches one product from the catalog API.
func (c *Client) audibleBook(ctx context.Context, region, asin string) (model.Book, error) {
	base, r, err := c.audibleURL(region)
	if err != nil {
		return model.Book{}, err
	}
	v := url.Values{}
	v.Set("response_groups", responseGroups)
	v.Set("image_sizes", "500")
	var one audibleSingle
	if err := c.getJSON(ctx, "audible", base+"/1.0/catalog/products/"+asin+"?"+v.Encode(), &one); err != nil {
		return model.Book{}, err
	}
	b := one.Product.toBook(r)
	// A marketplace that does not carry the ASIN answers 200 with a bare {asin} stub.
	if b.ASIN == "" || b.Title == "" {
		return model.Book{}, ErrNotFound
	}
	return b, nil
}
