package appapi

import (
	"net/http"
	"strings"
)

// maxAppDownloads bounds concurrent APK downloads. The routes are unauthenticated and clients
// behind Cloudflare/Traefik are not distinguishable, so the limits (this one and the request-rate
// caps in MountApp) are global.
const maxAppDownloads = 4

type appUpdateDTO struct {
	Available   bool   `json:"available"`
	Version     string `json:"version,omitempty"`
	VersionCode int    `json:"version_code,omitempty"`
	Size        int64  `json:"size,omitempty"`
	SHA256      string `json:"sha256,omitempty"`
	URL         string `json:"url,omitempty"`
}

func (a *app) appUpdate(w http.ResponseWriter, _ *http.Request) {
	if err := a.updateCap.Admit(); err != nil {
		a.fail(w, err)
		return
	}
	info, ok := a.d.Update.Info()
	if !ok {
		WriteJSON(w, http.StatusOK, appUpdateDTO{})
		return
	}
	WriteJSON(w, http.StatusOK, appUpdateDTO{
		Available: true, Version: info.VersionName, VersionCode: info.VersionCode, Size: info.Size,
		SHA256: info.SHA256, URL: strings.TrimRight(a.d.PublicURL, "/") + "/v1/app/update/apk",
	})
}

func (a *app) appUpdateAPK(w http.ResponseWriter, r *http.Request) {
	if err := a.apkCap.Admit(); err != nil {
		a.fail(w, err)
		return
	}
	content, info, ok := a.d.Update.APK()
	if !ok {
		WriteError(w, http.StatusNotFound, "not_found", "no app update is bundled with this server")
		return
	}
	select {
	case a.appDL <- struct{}{}:
		defer func() { <-a.appDL }()
	default:
		w.Header().Set("Retry-After", "15")
		WriteError(w, http.StatusServiceUnavailable, "upstream_failed", "too many downloads in progress, try again shortly")
		return
	}
	h := w.Header()
	h.Set("Content-Type", "application/vnd.android.package-archive")
	h.Set("Content-Disposition", `attachment; filename="audiobooks-`+info.VersionName+`.apk"`)
	h.Set("ETag", `"`+info.SHA256+`"`)
	http.ServeContent(w, r, "", info.ReleasedTime(), content)
}
