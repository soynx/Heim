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



