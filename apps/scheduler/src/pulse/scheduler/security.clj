(ns pulse.scheduler.security
  "The scheduler's policy on the headers of its responses (ADR-0018, ADR-0020). The mechanism is
  `pulse.platform.http.headers`. Its answers are JSON and nothing a browser should run or show,
  and no browser calls it: the same headers as the api's, without CORS or a page of documentation.")

(def headers
  "On every response, unless it sets one itself. The answers are about the running of the system,
  so nothing may be cached; the body is what the `Content-Type` says, never something a browser
  guessed; nothing may frame it, and nothing it links to is told where the reader came from."
  {"X-Content-Type-Options" "nosniff"
   "Referrer-Policy" "no-referrer"
   "X-Frame-Options" "DENY"
   "Permissions-Policy" "accelerometer=(), camera=(), geolocation=(), gyroscope=(), magnetometer=(), microphone=(), payment=(), usb=()"
   "Cross-Origin-Resource-Policy" "same-site"
   "Cache-Control" "no-store"
   "Content-Security-Policy" "default-src 'none'; frame-ancestors 'none'"})
