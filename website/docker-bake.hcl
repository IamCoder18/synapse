# Build targets consumed by docker/bake-action (see .github/workflows/
# docker.yml for push/tag publishes and docker-pr.yml for PR builds).
# Defining the build here keeps the Dockerfile, tags, cache config, and
# platform declarations in one file under the website/ source tree.
#
# CI builds each platform natively on its own runner (amd64 and arm64 jobs
# override `platform` via `--set`, avoiding QEMU emulation) and merges the
# two pushed digests into one multi-arch manifest list. A plain local
# `docker buildx bake synapse-website` still builds both platforms on a
# single node.
#
# The PR build target omits `cache-to` because fork pull requests don't have
# permission to write to the GHA cache; trying to export there fails the
# required check.
#
# Context is the repo root (not website/) because the build needs files
# outside the website/ source tree — most importantly the repo-root
# CHANGELOG.md, which the changelog parser reads at build time.
group "default" {
    targets = ["synapse-website"]
}

target "synapse-website" {
    context = "."
    dockerfile = "website/Dockerfile"
    platforms = ["linux/amd64", "linux/arm64"]
    cache-from = ["type=gha"]
    cache-to   = ["type=gha,mode=max"]
    tags       = [""]
}

target "synapse-website-pr" {
    context = "."
    dockerfile = "website/Dockerfile"
    platforms = ["linux/amd64", "linux/arm64"]
    cache-from = ["type=gha"]
    tags       = [""]
}
