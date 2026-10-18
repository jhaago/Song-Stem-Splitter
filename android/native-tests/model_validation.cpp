#include "separator.hpp"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <unistd.h>
int main() {
    const auto file = std::filesystem::temp_directory_path() /
                      ("vocal-bad-model-" + std::to_string(getpid()) + ".bin");
    auto reject = [&](const char *name) {
        auto b = vocal::make_demucs_backend();
        bool rejected = false;
        try {
            b->load(file);
        } catch (const std::runtime_error &) {
            rejected = true;
        }
        if (!rejected)
            throw std::runtime_error(name);
        std::cout << "PASS " << name << '\n';
    };
    {
        std::ofstream f(file, std::ios::binary);
        f << "dmc4";
    }
    reject("truncated model");
    {
        std::ofstream f(file, std::ios::binary | std::ios::trunc);
        f.seekp(83994360);
        f.put(0);
    }
    reject("wrong model magic");
    {
        std::fstream f(file, std::ios::binary | std::ios::in | std::ios::out);
        const unsigned char hdr[] = {0x34, 0x63, 0x6d, 0x64, 0x7f, 0xff,
                                     0xff, 0x7f, 0xff, 0xff, 0xff, 0x7f};
        f.write(reinterpret_cast<const char *>(hdr), sizeof(hdr));
    }
    reject("unbounded tensor rank/name rejected before allocation");
    std::filesystem::remove(file);
}
