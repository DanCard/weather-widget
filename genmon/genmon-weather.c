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
    char buf[1024];
    int n;

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

    // Set a short timeout (100ms) so genmon doesn't hang if the app is frozen
    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = 100000;
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, (const char*)&tv, sizeof tv);
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, (const char*)&tv, sizeof tv);

    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) == -1) {
        close(fd);
        goto fallback;
    }

    while ((n = read(fd, buf, sizeof(buf) - 1)) > 0) {
        buf[n] = '\0';
        printf("%s", buf);
    }
    
    close(fd);
    return 0;

fallback:
    // Grayed out "--" with a clear tooltip if the app isn't serving data.
    printf("<txt><span font='Sans Bold 20' foreground='#888888' line_height='0.6'>--</span></txt>\n");
    printf("<tool>Weather Widget: App not running</tool>\n");
    printf("<txtclick>#</txtclick>\n");
    return 0;
}