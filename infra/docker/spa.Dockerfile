# syntax=docker/dockerfile:1.7
#
# The clinical workspace (ADR-0030): its release, built in this file, and served by nginx with the configuration that
# pulse.spa.nginx makes from the headers and the names of the Ring server (infra/spa/nginx.conf).
#
#   docker build -f infra/docker/spa.Dockerfile -t pulse-spa .
#
# The context is the root of the repository. The page and the policy are for the addresses of the local stack (the api at
# localhost:4000, Keycloak at localhost:8080), which the realm registers.

# --- Node, which is copied from its own image -----------------------------------------------------------------------------------
FROM node:22.23.4-bookworm-slim AS node

# --- the release ----------------------------------------------------------------------------------------------------------------
FROM clojure:temurin-21-tools-deps-1.12.6.1673-bookworm-slim AS build
# pipefail: the build is judged by what shadow-cljs says of its warnings, not by the exit code of tee
SHELL ["/bin/bash", "-o", "pipefail", "-c"]
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm \
 && ln -s ../lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
WORKDIR /src

# The npm packages (React, keycloak-js, shadow-cljs) are a layer of their own, which stays until the lock changes
COPY apps/spa/package.json apps/spa/package-lock.json apps/spa/
RUN --mount=type=cache,target=/root/.npm \
    cd apps/spa && npm ci

# The sources, and what the release is put together from: the font, the tokens and the page (pulse.design.spa)
COPY deps.edn ./
COPY libs libs
COPY apps/spa apps/spa
COPY design/src design/src
COPY design/fonts design/fonts
COPY design/tokens.edn design/
COPY tools/design.clj tools/

# A release that has a warning is not a release (bb spa:build says the same): the summary of shadow-cljs must say 0 warnings
RUN --mount=type=cache,target=/root/.m2 \
    cd apps/spa && npx shadow-cljs release app 2>&1 | tee /tmp/shadow-cljs.log \
 && grep -Eq "Build completed\. \(.*, 0 warnings," /tmp/shadow-cljs.log
# The stylesheet, the icon and the font beside the script, and the page that loads the script by the name with its hash
RUN --mount=type=cache,target=/root/.m2 \
    clojure -M:spa-release

# --- the image ------------------------------------------------------------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.30.6-alpine3.24
LABEL org.opencontainers.image.title="pulse-spa" \
      org.opencontainers.image.description="Pulse clinical workspace: a ClojureScript single-page application, served by nginx (synthetic data only)" \
      org.opencontainers.image.licenses="MIT"
COPY infra/spa/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/apps/spa/target/release /usr/share/nginx/html
# The configuration must load (the base image runs as a user that is not root, as the container will)
RUN nginx -t
