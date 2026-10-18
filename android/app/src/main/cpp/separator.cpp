#include "separator.hpp"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <fstream>
namespace vocal {
namespace {
constexpr int64_t window = 30LL * kSampleRate, overlap = 2LL * kSampleRate,
                  stride = window - overlap;
void check(const std::atomic_bool &c) {
    if (c.load(std::memory_order_relaxed))
        throw Cancelled();
}
void read_pcm(std::ifstream &f, std::vector<float> &data) {
    f.read(reinterpret_cast<char *>(data.data()), static_cast<std::streamsize>(data.size() * 4));
    if (!f)
        throw std::runtime_error("Audio file is truncated or unreadable");
    for (float x : data)
        if (!std::isfinite(x) || std::abs(x) > 1e6f)
            throw std::runtime_error("Audio contains invalid samples");
}
} // namespace
void separate_files(const std::string &model, const std::string &source, const std::string &output,
                    int64_t frames, std::atomic_bool &cancelled, const Progress &progress,
                    Backend &backend) {
    static_assert(sizeof(float) == 4, "PCM requires 32-bit floats");
    check(cancelled);
    if (frames <= 0 || frames > kMaxFrames)
        throw std::runtime_error("Audio must be nonempty and at most 15 minutes");
    if (source == output || std::filesystem::exists(output))
        throw std::runtime_error("Output must be a new file, distinct from the original");
    const std::string partial = output + ".part";
    if (source == partial)
        throw std::runtime_error("Output must differ from original");
    if (std::filesystem::file_size(source) != static_cast<uintmax_t>(frames) * 8)
        throw std::runtime_error("Audio file length does not match its frame count");
    float last = 0;
    auto emit = [&](float p, const std::string &stage) {
        check(cancelled);
        p = std::max(last, std::min(1.f, p));
        last = p;
        if (progress)
            progress(p, stage);
        check(cancelled);
    };
    std::ifstream in(source, std::ios::binary);
    if (!in)
        throw std::runtime_error("Cannot open source audio");
    // Validate the entire stream using bounded memory before loading expensive tensors.
    std::vector<float> scan;
    bool silent = true;
    for (int64_t pos = 0; pos < frames;) {
        check(cancelled);
        int64_t n = std::min<int64_t>(65536, frames - pos);
        scan.resize(n * 2);
        read_pcm(in, scan);
        for (float x : scan)
            if (x != 0)
                silent = false;
        pos += n;
        emit(.02f * pos / frames, "Checking audio");
    }
    in.clear();
    in.seekg(0);
    scan.clear();
    scan.shrink_to_fit();
    struct Cleanup {
        std::string path;
        ~Cleanup() {
            std::error_code ec;
            std::filesystem::remove(path, ec);
        }
    } cleanup{partial};
    std::ofstream out(partial, std::ios::binary | std::ios::trunc);
    if (!out)
        throw std::runtime_error("Cannot create separated audio");
    if (silent) {
        std::vector<float> zero(65536 * 2, 0);
        for (int64_t p = 0; p < frames;) {
            check(cancelled);
            int64_t n = std::min<int64_t>(65536, frames - p);
            out.write(reinterpret_cast<const char *>(zero.data()), n * 8);
            if (!out)
                throw std::runtime_error("Could not write separated audio");
            p += n;
            emit(.02f + .96f * p / frames, "Silent audio");
        }
    } else {
        emit(.025f, "Loading separation model");
        backend.load(model);
        emit(.04f, "Model loaded");
        std::vector<float> tail;
        for (int64_t start = 0; start < frames; start += stride) {
            check(cancelled);
            int64_t n = std::min(window, frames - start);
            std::vector<float> x(n * 2);
            in.seekg(start * 8);
            read_pcm(in, x);
            bool chunk_silent = std::all_of(x.begin(), x.end(), [](float v) { return v == 0; });
            auto y =
                chunk_silent
                    ? std::vector<float>(x.size(), 0)
                    : backend.run(x, [&](float p, const std::string &s) {
                          emit(.04f + .93f * (start + std::clamp(p, 0.f, 1.f) * n) / frames, s);
                      });
            check(cancelled);
            if (y.size() != x.size())
                throw std::runtime_error("Separation returned the wrong audio length");
            for (float v : y)
                if (!std::isfinite(v))
                    throw std::runtime_error("Separation returned nonfinite samples");
            int64_t blended = std::min<int64_t>(tail.size() / 2, n);
            for (int64_t i = 0; i < blended; ++i) {
                float w = static_cast<float>(i + 1) / (blended + 1);
                for (int c = 0; c < 2; ++c)
                    y[i * 2 + c] = tail[i * 2 + c] * (1 - w) + y[i * 2 + c] * w;
            }
            bool final = start + n == frames;
            int64_t written = final ? n : stride;
            out.write(reinterpret_cast<const char *>(y.data()), written * 8);
            if (!out)
                throw std::runtime_error("Could not write separated audio (storage may be full)");
            if (final)
                break;
            tail.assign(y.begin() + written * 2, y.end());
        }
    }
    out.flush();
    if (!out)
        throw std::runtime_error("Could not flush separated audio");
    out.close();
    if (!out)
        throw std::runtime_error("Could not close separated audio");
    if (std::filesystem::file_size(partial) != static_cast<uintmax_t>(frames) * 8)
        throw std::runtime_error("Separated audio length mismatch");
    emit(.99f, "Finishing separation");
    // The final callback runs before promotion so a callback exception cannot leave a completed
    // file.
    emit(1, "Separation complete");
    check(cancelled);
    std::filesystem::rename(partial, output);
}
} // namespace vocal
