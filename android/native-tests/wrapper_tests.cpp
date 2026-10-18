#include "separator.hpp"
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <unistd.h>
using namespace vocal;
namespace fs = std::filesystem;
struct Identity : Backend {
    int loads = 0, runs = 0;
    void load(const std::string &) override { ++loads; }
    std::vector<float> run(const std::vector<float> &x, const Progress &p) override {
        ++runs;
        p(.5f, "test inference");
        p(1, "test inference");
        return x;
    }
};
static void require(bool ok, const char *msg) {
    if (!ok)
        throw std::runtime_error(msg);
}
static void write(const fs::path &path, const std::vector<float> &x) {
    std::ofstream f(path, std::ios::binary);
    f.write(reinterpret_cast<const char *>(x.data()), x.size() * sizeof(float));
}
static std::vector<float> read(const fs::path &path) {
    std::vector<float> x(fs::file_size(path) / 4);
    std::ifstream f(path, std::ios::binary);
    f.read(reinterpret_cast<char *>(x.data()), x.size() * 4);
    return x;
}
int main() {
    fs::path dir = fs::temp_directory_path() / ("vocal-native-tests-" + std::to_string(getpid()));
    fs::create_directories(dir);
    auto src = dir / "in.pcm", out = dir / "out.pcm";
    int checks = 0;
    auto test = [&](const char *name, auto fn) {
        try {
            fs::remove(out);
            fs::remove(out.string() + ".part");
            fn();
            ++checks;
            std::cout << "PASS " << name << '\n';
        } catch (...) {
            std::cerr << "FAIL " << name << '\n';
            throw;
        }
    };
    auto failure = [&](int64_t n, Identity &b, std::atomic_bool &c, Progress p = {}) {
        bool threw = false;
        try {
            separate_files("unused", src, out, n, c, p, b);
        } catch (const std::exception &) {
            threw = true;
        }
        require(threw, "expected failure");
        require(!fs::exists(out), "completed output left on error");
        require(!fs::exists(out.string() + ".part"), "partial output left on error");
    };
    test("rejects invalid frame count", [&] {
        Identity b;
        std::atomic_bool c{false};
        write(src, {0, 0});
        failure(0, b, c);
        failure(kMaxFrames + 1, b, c);
        require(b.loads == 0, "loaded invalid input");
    });
    test("rejects truncated and oversized PCM", [&] {
        Identity b;
        std::atomic_bool c{false};
        write(src, {0, 0});
        failure(2, b, c);
        write(src, {0, 0, 0, 0});
        failure(1, b, c);
    });
    test("rejects nonfinite audio", [&] {
        Identity b;
        std::atomic_bool c{false};
        write(src, {0, std::numeric_limits<float>::quiet_NaN()});
        failure(1, b, c);
        require(!b.loads, "model loaded before validation");
    });
    test("silence is finite and aligned without model inference", [&] {
        Identity b;
        std::atomic_bool c{false};
        write(src, std::vector<float>(88200));
        separate_files("unused", src, out, 44100, c, {}, b);
        require(read(out) == std::vector<float>(88200), "silence changed");
        require(!b.runs, "silence inferred");
    });
    test("very short audio preserves frame count", [&] {
        Identity b;
        std::atomic_bool c{false};
        write(src, {.2f, -.1f});
        separate_files("unused", src, out, 1, c, {}, b);
        require(read(out) == std::vector<float>({.2f, -.1f}), "short output wrong");
    });
    test("overlap windows preserve gain and monotonic progress", [&] {
        Identity b;
        std::atomic_bool c{false};
        std::vector<float> x(44100 * 65 * 2);
        for (size_t i = 0; i < x.size(); ++i)
            x[i] = .2f * std::sin(i * .007);
        write(src, x);
        float last = 0;
        separate_files(
            "unused", src, out, x.size() / 2, c,
            [&](float p, const std::string &) {
                require(p >= last && p <= 1, "progress regressed");
                last = p;
            },
            b);
        auto y = read(out);
        require(y.size() == x.size(), "output length differs");
        for (size_t i = 0; i < x.size(); ++i)
            require(std::abs(x[i] - y[i]) < 1e-6, "crossfade changed gain");
        require(b.runs >= 3 && b.loads == 1, "window/model lifetime wrong");
        require(last == 1, "progress incomplete");
    });
    test("pre-cancel leaves no output", [&] {
        Identity b;
        std::atomic_bool c{true};
        write(src, {.2f, .1f});
        failure(1, b, c);
    });
    test("cancel during inference removes partial output", [&] {
        Identity b;
        std::atomic_bool c{false};
        write(src, std::vector<float>(44100 * 35 * 2, .1f));
        failure(44100 * 35, b, c, [&](float p, const std::string &) {
            if (p > .1f)
                c = true;
        });
    });
    test("backend nonfinite result is rejected", [&] {
        struct Bad : Identity {
            std::vector<float> run(const std::vector<float> &x, const Progress &) override {
                return std::vector<float>(x.size(), std::numeric_limits<float>::infinity());
            }
        } b;
        std::atomic_bool c{false};
        write(src, {.2f, .1f});
        failure(1, b, c);
    });
    test("callback error at completion removes partial output", [&] {
        Identity b;
        std::atomic_bool c{false};
        write(src, {.2f, .1f});
        failure(1, b, c, [](float p, const std::string &) {
            if (p == 1)
                throw std::runtime_error("callback failed");
        });
    });
    test("existing completed output is preserved", [&] {
        Identity b;
        std::atomic_bool c{false};
        write(src, {.2f, .1f});
        write(out, {.7f, .8f});
        bool threw = false;
        try {
            separate_files("unused", src, out, 1, c, {}, b);
        } catch (const std::exception &) {
            threw = true;
        }
        require(threw, "existing output accepted");
        require(read(out) == std::vector<float>({.7f, .8f}), "existing output damaged");
    });
    fs::remove_all(dir);
    std::cout << checks << " wrapper tests passed\n";
}
