package debrid

import "sort"

// Registry maps Provider.Name() to the provider.
type Registry map[string]Provider

// NewRegistry builds a Registry from providers; a later provider with the same name wins.
func NewRegistry(providers ...Provider) Registry {
	r := make(Registry, len(providers))
	for _, p := range providers {
		r[p.Name()] = p
	}
	return r
}

// Get returns the provider registered under name.
func (r Registry) Get(name string) (Provider, bool) {
	p, ok := r[name]
	return p, ok
}

// Names returns the registered provider names, sorted.
func (r Registry) Names() []string {
	names := make([]string, 0, len(r))
	for name := range r {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}
