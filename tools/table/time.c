/* Child CPU and peak RSS for tables-ab when macOS time -l cannot read
 * kern.clockrate in a sandbox. wait4 measures the same child resource usage. */
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc < 3) return 2;
    FILE *out = fopen(argv[1], "w");
    if (!out) { perror("measurement output"); return 2; }
    pid_t child = fork();
    if (child < 0) { perror("fork"); fclose(out); return 2; }
    if (!child) {
        fclose(out);
        execvp(argv[2], argv + 2);
        perror("execvp");
        _exit(127);
    }
    struct rusage usage;
    int status;
    while (wait4(child, &status, 0, &usage) < 0) {
        if (errno != EINTR) { perror("wait4"); fclose(out); return 2; }
    }
    long long rss = usage.ru_maxrss;
#ifndef __APPLE__
    rss *= 1024;
#endif
    fprintf(out, "%.6f user %.6f sys\n%lld maximum resident set size\n",
            usage.ru_utime.tv_sec + usage.ru_utime.tv_usec / 1000000.0,
            usage.ru_stime.tv_sec + usage.ru_stime.tv_usec / 1000000.0, rss);
    fclose(out);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 128 + WTERMSIG(status);
}
