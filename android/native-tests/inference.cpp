#include "separator.hpp"
#include <cstdlib>
#include <iostream>
#include <sys/resource.h>
int main(int argc, char **argv) {
    if (argc != 5) {
        std::cerr << "Usage: vocal_inference MODEL INPUT_PCM OUTPUT_PCM FRAMES\n";
        return 2;
    }
    try {
        std::atomic_bool c{false};
        auto backend = vocal::make_demucs_backend();
        vocal::separate_files(
            argv[1], argv[2], argv[3], std::stoll(argv[4]), c,
            [&c](float p, const std::string &s) {
                if (const char *limit = std::getenv("VOCAL_CANCEL_AT")) {
                    if (p >= std::atof(limit))
                        c = true;
                }
                rusage usage{};
                getrusage(RUSAGE_SELF, &usage);
                std::cerr << "[rss_kib=" << usage.ru_maxrss << "] " << p << " " << s << '\n';
            },
            *backend);
        return 0;
    } catch (const std::exception &e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
