# Synapse website container

The Synapse marketing + docs site is published as a prebuilt Docker image at **GitHub Container Registry**:

```
ghcr.io/iamcoder18/synapse-website
```

Tags:

- `:latest` — updated on every merge to `main`
- `:0.4.1`, `0.4`, `0`, … — updated on every `v*` Git tag (synced with the Maven/Java package version)
- `:<sha>` — every commit, for reproducibility

## Run with docker compose

Copy `docker-compose.yaml` to any directory and:

```bash
docker compose up -d
curl http://localhost:8080/healthz   # → ok
open  http://localhost:8080/
```

Pin a version:

```bash
SYNAPSE_SITE_IMAGE=ghcr.io/iamcoder18/synapse-website:0.4.1 \
  docker compose up -d
```

## Run without compose

```bash
docker run --rm -p 8080:8080 --name synapse-website \
  ghcr.io/iamcoder18/synapse-website:latest
```

## Image contents

- `node:lts-alpine` build stage compiles the Astro site to `dist/`.
- `nginx:alpine` runtime serves the static files on port `8080` with the
  custom `nginx.conf` from this repo (immutable cache for hashed assets,
  revalidatable cache for HTML, gzip/brotli for text, `Link` headers for
  AI endpoints, `=404` fallback).
- `/healthz` returns `200 ok` for compose healthchecks.

## Build locally (optional)

The image is built by `.github/workflows/docker.yml`. To build it yourself:

```bash
cd website
docker buildx bake synapse-website --push \
  --set "synapse-website.tags=ghcr.io/iamcoder18/synapse-website:dev"
```

## Source

See [`Dockerfile`](./Dockerfile), [`nginx.conf`](./nginx.conf), and [`docker-bake.hcl`](./docker-bake.hcl).
