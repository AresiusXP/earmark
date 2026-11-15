package debrid

import (
	"context"
	"reflect"
	"testing"
)

type stubProvider string

func (s stubProvider) Name() string                         { return string(s) }
func (stubProvider) Validate(context.Context, string) error { return nil }
func (stubProvider) CheckCached(context.Context, string, []string) (map[string]Cached, error) {
	return nil, nil
}
func (stubProvider) Resolve(context.Context, string, ResolveRequest) (ResolveResult, error) {
	return ResolveResult{}, nil
}

func TestRegistry(t *testing.T) {
	r := NewRegistry(stubProvider("torbox"), stubProvider("realdebrid"))
	tests := []struct {
		name string
		want bool
	}{{"torbox", true}, {"realdebrid", true}, {"alldebrid", false}, {"", false}}
	for _, tt := range tests {
		p, ok := r.Get(tt.name)
		if ok != tt.want || (ok && p.Name() != tt.name) {
			t.Errorf("Get(%q) = %v, %v; want ok=%v", tt.name, p, ok, tt.want)
		}
	}
	if got, want := r.Names(), []string{"realdebrid", "torbox"}; !reflect.DeepEqual(got, want) {
		t.Errorf("Names() = %v, want %v", got, want)
	}
	var nilReg Registry
	if _, ok := nilReg.Get("torbox"); ok {
		t.Error("nil registry must not find anything")
	}
}
