# Heim

A simple clean homepage dashboard made for selfhosters. Provides a web-dashboard for your apps, based on Services rather than URLs.


This is basicly my own clone of the "linuxserver/Heimdall" project, together with my own tweaks and a bit diffrent behavior.


## Differences between Heim and Heimdall.
- Each widget in the dashboard is treated as a own service or host, instead of fixing each widget on a single URL like heimdall does.
- Each widget can have mutliple links or urls
- Provides some extra features like:
    - http-probing the URL (shows green and red indicators for each link) (optional feature, needs to be toggled on for each widget individually, we cant garentee that this will work with every endpoint)
    - custom background image
    - custom CSS
    - custom icons for each widget
- optional login-page
- tagging widgets
- unlimited custom widget collections


## Running with Docker
```sh
docker compose up -d --build
```
Heim listens on port 8000 and keeps everything (config, icons, uploads) in `/data`, which the compose file binds to `./data`.

| Variable | Default | Meaning |
|---|---|---|
| `PUID` / `PGID` | `1000` | User and group Heim runs as. At startup the container makes `/data` owned by them, so a `./data` directory created by Docker as root works. If you start the container with `--user`, that user is used instead and `/data` must already be writable for it. |
| `HEIM_RESET_AUTH` | `false` | Set to `true` for one start to clear the login credentials and turn both login toggles off. |
| `HEIM_SECURE_COOKIES` | `false` | Set to `true` when Heim is only reached over HTTPS, so the session and CSRF cookies are marked `Secure`. |

Behind a reverse proxy, Heim honours the `X-Forwarded-*` headers the proxy sends (scheme, host, port), so make sure your proxy sets them and that Heim is not reachable around the proxy.
