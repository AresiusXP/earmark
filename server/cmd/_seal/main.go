package main

import (
	"fmt"
	"os"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/userconfig"
)

func main() {
	s, _ := userconfig.NewSealer(os.Getenv("CONFIG_SECRET"))
	t, err := s.Seal(userconfig.Config{Debrid: map[string]string{"torbox": "fake-key-for-smoke-test"}, Languages: []string{"en"}})
	if err != nil {
		panic(err)
	}
	fmt.Print(t)
}
