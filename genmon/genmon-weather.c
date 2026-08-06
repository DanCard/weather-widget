#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

/**
 * Lightweight IPC client for XFCE genmon-weather.
 * Connects to the running Weather Widget desktop app via a Unix Domain Socket,
 * prints the Pango markup, and exits.
 */

int main() {
    struct sockaddr_un addr;
    int fd;
    char buf[8192];
    size_t total = 0;
    ssize_t n;

    if ((fd = socket(AF_UNIX, SOCK_STREAM, 0)) == -1) {
        goto fallback;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;

    const char *data_home = getenv("XDG_DATA_HOME");
    const char *home = getenv("HOME");
    
    if (data_home && strlen(data_home) > 0) {
        snprintf(addr.sun_path, sizeof(addr.sun_path), "%s/weather-widget/weather.sock", data_home);
    } else if (home && strlen(home) > 0) {
        snprintf(addr.sun_path, sizeof(addr.sun_path), "%s/.local/share/weather-widget/weather.sock", home);
    } else {
        goto fallback;
    }

    // Bound the wait so genmon can't hang on a frozen app, but keep it well clear of a
    // cold serve: the daemon answers from a cached string in well under a millisecond, and
    // only the very first connect after startup computes the markup inline. A 100ms budget
    // used to expire before that first byte arrived, which blanked the panel entirely.
    struct timeval tv;
    tv.tv_sec = 2;
    tv.tv_usec = 0;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof tv);
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tv, sizeof tv);

    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) == -1) {
        close(fd);
        goto fallback;
    }

    // Accumulate the whole response before printing. Printing each chunk as it arrived meant a
    // mid-stream timeout emitted truncated markup, which the panel renders as garbage; buffering
    // keeps the output all-or-nothing so a short read falls through to the placeholder instead.
    while (total < sizeof(buf) - 1 &&
           (n = read(fd, buf + total, sizeof(buf) - 1 - total)) > 0) {
        total += (size_t)n;
    }
    close(fd);

    // A read timeout leaves total == 0. That must NOT be reported as success: genmon renders
    // empty output as the literal string "(genmon)", so returning here without printing is what
    // made the panel look broken rather than merely stale.
    if (total == 0) {
        goto fallback;
    }

    buf[total] = '\0';
    printf("%s", buf);
    return 0;

fallback:
    // Grayed out "--" with a clear tooltip if the app isn't serving data.
    printf("<txt><span font='Sans Bold 20' foreground='#888888' line_height='0.6'>--</span></txt>\n");
    printf("<tool>Weather Widget: App not running</tool>\n");
    printf("<txtclick>#</txtclick>\n");
    return 0;
}