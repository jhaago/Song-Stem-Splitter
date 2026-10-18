#pragma once
#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
namespace vocal {
constexpr int kSampleRate = 44100;
constexpr int64_t kMaxFrames = 15LL * 60 * kSampleRate;
using Progress = std::function<void(float, const std::string &)>;
struct Cancelled : std::runtime_error {
    Cancelled() : std::runtime_error("Separation cancelled") {}
};
class Backend {
  public:
    virtual ~Backend() = default;
    virtual void load(const std::string &path) = 0;
    virtual std::vector<float> run(const std::vector<float> &stereo, const Progress &progress) = 0;
};
void separate_files(const std::string &model, const std::string &source, const std::string &output,
                    int64_t frames, std::atomic_bool &cancelled, const Progress &progress,
                    Backend &backend);
std::unique_ptr<Backend> make_demucs_backend();
} // namespace vocal
