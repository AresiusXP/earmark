# ─── Build stage ───
# Base images are pinned by multi-arch index digest (linux/amd64 + linux/arm64); Renovate bumps tag and digest together.
FROM golang:1.26-bookworm@sha256:ea47ee45f1284d60f9cc09cb2c3bb4f508c256b640bd8bb30342ff13d1adcb52 AS builder

WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

# Not `COPY . .`: update/ (the APK baked in by release.yml) belongs to the final stage only.
COPY cmd ./cmd
COPY internal ./internal

ARG VERSION=dev
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath \
    -ldflags="-s -w -X main.version=${VERSION}" \
    -o /out/addon ./cmd/addon

# ─── Production stage ───
FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab

COPY --from=builder /out/addon /addon
# audiobooks.apk + update.json of the same version (see docs/DESIGN.md, Delivery). The directory always
# exists (.gitkeep) but may hold no bundle: the server then reports "no update bundled".
COPY update/ /update/

ENV PORT=8080
ENV APP_UPDATE_DIR=/update
EXPOSE 8080
USER nonroot:nonroot

ENTRYPOINT ["/addon"]
