package httpapi

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"math"
	"net/http"
	"sync"
	"time"
)

var (
	logoPNG       = sync.OnceValue(func() []byte { return encodePNG(drawLogo(256)) })
	backgroundPNG = sync.OnceValue(func() []byte { return encodePNG(drawBackground(960, 540)) })
)

func encodePNG(img image.Image) []byte {
	var buf bytes.Buffer
	_ = png.Encode(&buf, img)
	return buf.Bytes()
}

func servePNG(data func() []byte) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "public, max-age=86400")
		http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(data()))
	}
}

func lerp(a, b uint8, t float64) uint8 { return uint8(float64(a) + (float64(b)-float64(a))*t) }

func gradient(x, y, w, h int) color.RGBA {
	t := (float64(x)/float64(w) + float64(y)/float64(h)) / 2
	return color.RGBA{lerp(0x1b, 0x5b, t), lerp(0x1f, 0x2a, t), lerp(0x3b, 0x86, t), 0xff}
}

func drawBackground(w, h int) image.Image {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := range h {
		for x := range w {
			img.SetRGBA(x, y, gradient(x, y, w, h))
		}
	}
	return img
}

// drawLogo paints a headphones glyph on the gradient: an arc plus two ear cups.
func drawLogo(n int) image.Image {
	img := image.NewRGBA(image.Rect(0, 0, n, n))
	white := color.RGBA{0xff, 0xff, 0xff, 0xff}
	c := float64(n) / 2
	r1, r2 := float64(n)*0.30, float64(n)*0.37
	cupW, cupH := float64(n)*0.12, float64(n)*0.26
	for y := range n {
		for x := range n {
			img.SetRGBA(x, y, gradient(x, y, n, n))
			fx, fy := float64(x)-c, float64(y)-c*1.1
			d := math.Hypot(fx, fy)
			arc := d >= r1 && d <= r2 && fy <= 0
			left := math.Abs(fx+(r1+r2)/2) <= cupW/2 && fy >= 0 && fy <= cupH
			right := math.Abs(fx-(r1+r2)/2) <= cupW/2 && fy >= 0 && fy <= cupH
			if arc || left || right {
				img.SetRGBA(x, y, white)
			}
		}
	}
	return img
}
