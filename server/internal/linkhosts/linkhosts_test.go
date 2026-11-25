package linkhosts

import (
	"strings"
	"testing"
)

func TestAllowlist(t *testing.T) {
	l := New(nil)
	cases := []struct {
		provider, link string
		ok             bool
	}{
		{"torbox", "https://nexus-12.weur.tb-cdn.st/dl/abc?token=x", true},
		{"torbox", "https://tb-cdn.st/x", true},
		{"torbox", "https://store-3.torbox.app/x", true},
		{"torbox", "https://NEXUS.TB-CDN.ST/x", true},
		{"torbox", "https://nexus.tb-cdn.st:443/x", true},
		{"torbox", "https://tb-cdn.st.evil.com/x", false},
		{"torbox", "https://evil-tb-cdn.st/x", false},
		{"torbox", "https://nexus.tb-cdn.st.evil.com/x", false},
		{"torbox", "http://nexus.tb-cdn.st/x", false},
		{"torbox", "https://nexus.tb-cdn.st:8080/x", false},
		{"torbox", "https://user:pw@nexus.tb-cdn.st/x", false},
		{"torbox", "https://tb-cdn.st@evil.com/x", false},
		{"torbox", `https://tb-cdn.st\@evil.com/x`, false},
		{"torbox", "https://nexus.tb-cdn.st./x", false},
		{"torbox", "https://127.0.0.1/x", false},
		{"torbox", "https://[::1]/x", false},
		{"torbox", "//nexus.tb-cdn.st/x", false},
		{"torbox", "https://nexus.tb-cdn.st/x y", false},
		{"torbox", "https://nexus.tb-cdn.st/x\r\nSet-Cookie: a=b", false},
		{"torbox", "https://nexus.tb-cdn.st/\x00", false},
		{"torbox", "https://22-4.download.real-debrid.com/x", false}, // another provider's host
		{"torbox", "", false},
		{"torbox", "https://nexus.tb-cdn.st/" + strings.Repeat("a", 5000), false},
		{"realdebrid", "https://22-4.download.real-debrid.com/d/ABC", true},
		{"realdebrid", "https://real-debrid.com/d/ABC", true},
		{"realdebrid", "https://abc.real-debrid.cloud/d/ABC", true},
		{"realdebrid", "https://x.rdb.so/d/ABC", true},
		{"realdebrid", "https://real-debrid.com.evil.com/x", false},
		{"realdebrid", "https://fakerdb.so/x", false},
		{"realdebrid", "http://x.rdb.so/x", false},
		{"realdebrid", "https://nexus.tb-cdn.st/x", false},
		{"premiumize", "https://nexus.tb-cdn.st/x", false}, // unknown provider: nothing is allowed
	}
	for _, c := range cases {
		got, err := l.Check(c.provider, c.link)
		if (err == nil) != c.ok {
			t.Errorf("%s %q: err = %v, want ok=%v", c.provider, c.link, err, c.ok)
		}
		if err != nil && got != "" {
			t.Errorf("%q: a refused link is returned", c.link)
		}
		if err != nil && strings.Contains(err.Error(), "tb-cdn") {
			t.Errorf("the error must not echo the link: %v", err)
		}
	}
}

func TestAllowlistExtraHosts(t *testing.T) {
	extra, err := Parse("realdebrid:new-cdn.example, torbox:TB-NEW.example")
	if err != nil {
		t.Fatal(err)
	}
	l := New(extra)
	for _, link := range []string{"https://a.new-cdn.example/x", "https://new-cdn.example/x"} {
		if _, err := l.Check("realdebrid", link); err != nil {
			t.Errorf("%s: %v", link, err)
		}
		if _, err := l.Check("torbox", link); err == nil {
			t.Errorf("%s: extra hosts are per provider", link)
		}
	}
	if _, err := l.Check("torbox", "https://a.tb-new.example/x"); err != nil {
		t.Errorf("extra suffix is lowercased: %v", err)
	}
	if _, err := New(nil).Check("realdebrid", "https://a.new-cdn.example/x"); err == nil {
		t.Error("extras must not leak into the built-in table")
	}
	for _, bad := range []string{"realdebrid", "realdebrid:", "realdebrid:com", "premiumize:x.example", "realdebrid:.x.example", "realdebrid:x.example/", "realdebrid:*.x.example"} {
		if _, err := Parse(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
	if m, err := Parse(""); err != nil || len(m) != 0 {
		t.Errorf("empty: %v %v", m, err)
	}
}
