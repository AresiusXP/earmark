package appapi

import (
	"errors"
	"net/http"
	"time"

	"github.com/AresiusXP/earmark/server/internal/accounts"
	"github.com/AresiusXP/earmark/server/internal/userkeys"
)

// ---- DTOs (the contract's JSON shapes) ----------------------------------------------------

type settingsDTO struct {
	Region            string   `json:"region"`
	Languages         []string `json:"languages"`
	PreferFormat      string   `json:"prefer_format"`
	PreferredProvider *string  `json:"preferred_provider"`
	SourceMode        string   `json:"source_mode"`
	P2PEnabled        bool     `json:"p2p_enabled"`
}

type meDTO struct {
	ID          string      `json:"id"`
	DisplayName string      `json:"display_name"`
	Role        string      `json:"role"`
	Settings    settingsDTO `json:"settings"`
	Google      googleDTO   `json:"google"`
}

type sessionDTO struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int    `json:"expires_in"`
	TokenType    string `json:"token_type"`
	User         meDTO  `json:"user"`
}

type deviceDTO struct {
	ID         string     `json:"id"`
	Name       string     `json:"name"`
	CreatedAt  time.Time  `json:"created_at"`
	LastSeenAt *time.Time `json:"last_seen_at"`
	Current    bool       `json:"current"`
}

type debridDTO struct {
	Provider         string     `json:"provider"`
	Configured       bool       `json:"configured"`
	Status           string     `json:"status,omitempty"`
	ValidatedAt      *time.Time `json:"validated_at"`
	LastError        *string    `json:"last_error"`
	SharedWithOthers bool       `json:"shared_with_others"`
}

type adminUserDTO struct {
	ID           string          `json:"id"`
	DisplayName  string          `json:"display_name"`
	Role         string          `json:"role"`
	Disabled     bool            `json:"disabled"`
	CreatedAt    time.Time       `json:"created_at"`
	LastSeenAt   *time.Time      `json:"last_seen_at"`
	DeviceCount  int             `json:"device_count"`
	Debrid       map[string]bool `json:"debrid"`
	GoogleLinked bool            `json:"google_linked"`
	GoogleEmail  *string         `json:"google_email,omitempty"`
}

type inviteDTO struct {
	ID        string    `json:"id"`
	Note      string    `json:"note"`
	CreatedAt time.Time `json:"created_at"`
	ExpiresAt time.Time `json:"expires_at"`
}

func toMe(u accounts.User) meDTO {
	return meDTO{ID: u.ID, DisplayName: u.DisplayName, Role: string(u.Role), Settings: settingsDTO{
		Region: u.Settings.Region, Languages: nonNil(u.Settings.Languages), PreferFormat: u.Settings.PreferFormat,
		PreferredProvider: u.Settings.PreferredProvider, SourceMode: u.Settings.SourceMode, P2PEnabled: u.Settings.P2PEnabled,
	}, Google: toGoogle(u.GoogleEmail)}
}

func toSession(s accounts.Session) sessionDTO {
	return sessionDTO{AccessToken: s.AccessToken, RefreshToken: s.RefreshToken, ExpiresIn: s.ExpiresIn, TokenType: "Bearer", User: toMe(s.User)}
}

func toDebrid(s userkeys.Status) debridDTO {
	d := debridDTO{Provider: s.Provider, Configured: s.Configured, ValidatedAt: s.ValidatedAt, SharedWithOthers: s.SharedWithOthers}
	if s.Configured {
		d.Status = s.State
		if s.LastError != "" {
			e := s.LastError
			d.LastError = &e
		}
	}
	return d
}

func nonNil[T any](s []T) []T {
	if s == nil {
		return []T{}
	}
	return s
}

// ---- meta and auth ------------------------------------------------------------------------

func (a *API) version(w http.ResponseWriter, r *http.Request) {
	boot, err := a.acc.Bootstrapped(r.Context())
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"version": a.ver, "api_version": APIVersion, "bootstrapped": boot, "google_enabled": a.acc.GoogleEnabled()})
}

func (a *API) bootstrap(w http.ResponseWriter, r *http.Request) {
	var in struct {
		InstancePassword string `json:"instance_password"`
		DisplayName      string `json:"display_name"`
		DeviceName       string `json:"device_name"`
	}
	if !decode(w, r, &in) {
		return
	}
	s, err := a.acc.Bootstrap(r.Context(), in.InstancePassword, in.DisplayName, in.DeviceName)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toSession(s))
}

func (a *API) join(w http.ResponseWriter, r *http.Request) {
	var in struct {
		InviteCode  string `json:"invite_code"`
		DisplayName string `json:"display_name"`
		DeviceName  string `json:"device_name"`
	}
	if !decode(w, r, &in) {
		return
	}
	s, err := a.acc.Join(r.Context(), in.InviteCode, in.DisplayName, in.DeviceName)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toSession(s))
}

func (a *API) link(w http.ResponseWriter, r *http.Request) {
	var in struct {
		LinkCode   string `json:"link_code"`
		DeviceName string `json:"device_name"`
	}
	if !decode(w, r, &in) {
		return
	}
	s, err := a.acc.Link(r.Context(), in.LinkCode, in.DeviceName)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toSession(s))
}

func (a *API) refresh(w http.ResponseWriter, r *http.Request) {
	var in struct {
		RefreshToken string `json:"refresh_token"`
	}
	if !decode(w, r, &in) {
		return
	}
	s, err := a.acc.Refresh(r.Context(), in.RefreshToken)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toSession(s))
}

// ---- me -----------------------------------------------------------------------------------

func (a *API) principal(w http.ResponseWriter, r *http.Request) (accounts.Principal, bool) {
	p, ok := accounts.PrincipalFrom(r.Context())
	if !ok {
		WriteError(w, http.StatusUnauthorized, "unauthorized", "")
	}
	return p, ok
}

func (a *API) getMe(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	u, err := a.acc.Me(r.Context(), p.UserID)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toMe(u))
}

func (a *API) patchMe(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	var in struct {
		DisplayName *string                 `json:"display_name"`
		Settings    *accounts.SettingsPatch `json:"settings"`
	}
	if !decode(w, r, &in) {
		return
	}
	var patch accounts.SettingsPatch
	if in.Settings != nil {
		patch = *in.Settings
	}
	u, err := a.acc.UpdateMe(r.Context(), p.UserID, in.DisplayName, patch)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toMe(u))
}

func (a *API) listDevices(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	ds, err := a.acc.Devices(r.Context(), p)
	if err != nil {
		a.fail(w, err)
		return
	}
	out := make([]deviceDTO, 0, len(ds))
	for _, d := range ds {
		out = append(out, deviceDTO{ID: d.ID, Name: d.Name, CreatedAt: d.CreatedAt, LastSeenAt: d.LastSeenAt, Current: d.Current})
	}
	WriteJSON(w, http.StatusOK, map[string]any{"devices": out})
}

func (a *API) revokeDevice(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	if err := a.acc.RevokeDevice(r.Context(), p, r.PathValue("deviceId")); err != nil {
		a.fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *API) createLinkCode(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	code, exp, err := a.acc.CreateLinkCode(r.Context(), p)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"link_code": code, "expires_at": exp})
}

// ---- debrid keys --------------------------------------------------------------------------

func (a *API) listDebrid(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	sts, err := a.key.List(r.Context(), p.UserID)
	if err != nil {
		a.fail(w, err)
		return
	}
	out := make([]debridDTO, 0, len(sts))
	for _, s := range sts {
		out = append(out, toDebrid(s))
	}
	WriteJSON(w, http.StatusOK, map[string]any{"providers": out})
}

func (a *API) putDebrid(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	var in struct {
		APIKey string `json:"api_key"`
	}
	if !decode(w, r, &in) {
		return
	}
	st, err := a.key.Put(r.Context(), p.UserID, r.PathValue("provider"), in.APIKey)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toDebrid(st))
}

func (a *API) recheckDebrid(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	st, err := a.key.Recheck(r.Context(), p.UserID, r.PathValue("provider"))
	if errors.Is(err, userkeys.ErrNoKey) {
		WriteError(w, http.StatusNotFound, "not_found", "no key stored for this provider")
		return
	}
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toDebrid(st))
}

func (a *API) deleteDebrid(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	if err := a.key.Delete(r.Context(), p.UserID, r.PathValue("provider")); err != nil {
		a.fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- admin --------------------------------------------------------------------------------

func (a *API) toAdminUser(u accounts.AdminUser) adminUserDTO {
	debrid := make(map[string]bool, len(a.key.Providers()))
	for _, name := range a.key.Providers() {
		debrid[name] = u.Debrid[name]
	}
	return adminUserDTO{
		ID: u.ID, DisplayName: u.DisplayName, Role: string(u.Role), Disabled: u.Disabled, CreatedAt: u.CreatedAt,
		LastSeenAt: u.LastSeenAt, DeviceCount: u.DeviceCount, Debrid: debrid,
		GoogleLinked: u.GoogleEmail != nil, GoogleEmail: u.GoogleEmail,
	}
}

func (a *API) adminListUsers(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	us, err := a.acc.ListUsers(r.Context(), p)
	if err != nil {
		a.fail(w, err)
		return
	}
	out := make([]adminUserDTO, 0, len(us))
	for _, u := range us {
		out = append(out, a.toAdminUser(u))
	}
	WriteJSON(w, http.StatusOK, map[string]any{"users": out})
}

func (a *API) adminUpdateUser(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	var in struct {
		Disabled *bool `json:"disabled"`
	}
	if !decode(w, r, &in) {
		return
	}
	if in.Disabled == nil {
		WriteError(w, http.StatusBadRequest, "invalid_request", "nothing to update")
		return
	}
	u, err := a.acc.SetDisabled(r.Context(), p, r.PathValue("userId"), *in.Disabled)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, a.toAdminUser(u))
}

func (a *API) adminListInvites(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	invs, err := a.acc.ListInvites(r.Context(), p)
	if err != nil {
		a.fail(w, err)
		return
	}
	out := make([]inviteDTO, 0, len(invs))
	for _, i := range invs {
		out = append(out, inviteDTO{ID: i.ID, Note: i.Note, CreatedAt: i.CreatedAt, ExpiresAt: i.ExpiresAt})
	}
	WriteJSON(w, http.StatusOK, map[string]any{"invites": out})
}

func (a *API) adminCreateInvite(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	var in struct {
		Note           string `json:"note"`
		ExpiresInHours *int   `json:"expires_in_hours"`
	}
	if !decodeOptional(w, r, &in) {
		return
	}
	var ttl time.Duration
	if in.ExpiresInHours != nil {
		if *in.ExpiresInHours < 1 || *in.ExpiresInHours > 720 {
			WriteError(w, http.StatusBadRequest, "invalid_request", "expires_in_hours must be 1-720")
			return
		}
		ttl = time.Duration(*in.ExpiresInHours) * time.Hour
	}
	inv, code, err := a.acc.CreateInvite(r.Context(), p, in.Note, ttl)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{
		"id": inv.ID, "note": inv.Note, "created_at": inv.CreatedAt, "expires_at": inv.ExpiresAt, "code": code,
	})
}

func (a *API) adminRevokeInvite(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	if err := a.acc.RevokeInvite(r.Context(), p, r.PathValue("inviteId")); err != nil {
		a.fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
