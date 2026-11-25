# ─── Build stage ───
FROM golang:1.26-bookworm AS builder

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
FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=builder /out/addon /addon
# audiobooks.apk + update.json of the same version (see docs/DESIGN.md, Delivery). The directory always
# exists (.gitkeep) but may hold no bundle: the server then reports "no update bundled".
COPY update/ /update/

ENV PORT=8080
ENV APP_UPDATE_DIR=/update
EXPOSE 8080
USER nonroot:nonroot

ENTRYPOINT ["/addon"]
