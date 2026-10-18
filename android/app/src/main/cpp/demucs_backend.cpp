#include "model.hpp"
#include "model_manifest.hpp"
#include "separator.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <filesystem>
#include <fstream>
namespace vocal {
namespace {
constexpr uint64_t model_bytes = 83994361;
int32_t word(std::ifstream &f) {
    unsigned char b[4];
    f.read(reinterpret_cast<char *>(b), 4);
    if (!f)
        throw std::runtime_error("Truncated separation model");
    return static_cast<int32_t>(uint32_t(b[0]) | uint32_t(b[1]) << 8 | uint32_t(b[2]) << 16 |
                                uint32_t(b[3]) << 24);
}
// Upstream loader expects fixed architecture tensors. Validate every field before it sees a file.
void validate_model(const std::string &path) {
    if (std::filesystem::file_size(path) != model_bytes)
        throw std::runtime_error("Separation model has wrong size; download it again");
    std::ifstream f(path, std::ios::binary);
    if (word(f) != 0x646d6334)
        throw std::runtime_error("Expected the pinned four-source HTDemucs model");
    for (const auto &expected : kModelManifest) {
        int rank = word(f), length = word(f);
        if (rank != expected.rank || length <= 0 || length > 512)
            throw std::runtime_error("Invalid model tensor metadata");
        uint64_t count = 1;
        for (int i = 0; i < rank; ++i) {
            int d = word(f);
            if (d != expected.dims[i])
                throw std::runtime_error("Model tensor shape mismatch");
            count *= d;
        }
        std::string name(length, '\0');
        f.read(name.data(), length);
        if (!f || name != expected.name)
            throw std::runtime_error("Model tensor name mismatch");
        // FP16 data must be finite, including a corrupt model with otherwise valid headers.
        std::array<unsigned char, 16384> bytes{};
        uint64_t left = count * 2;
        while (left) {
            size_t n = std::min<uint64_t>(left, bytes.size());
            f.read(reinterpret_cast<char *>(bytes.data()), n);
            if (!f)
                throw std::runtime_error("Truncated model tensor");
            for (size_t i = 1; i < n; i += 2)
                if ((bytes[i] & 0x7c) == 0x7c)
                    throw std::runtime_error("Model contains nonfinite weights");
            left -= n;
        }
    }
    if (f.peek() != std::char_traits<char>::eof())
        throw std::runtime_error("Model contains extra tensor data");
}
class DemucsBackend final : public Backend {
    std::unique_ptr<demucscpp::demucs_model> model_;

  public:
    void load(const std::string &path) override {
        validate_model(path);
        model_ = std::make_unique<demucscpp::demucs_model>();
        if (!demucscpp::load_demucs_model(path, model_.get()) || !model_->is_4sources)
            throw std::runtime_error("Could not load HTDemucs model; download it again");
        // EIGEN_DONT_PARALLELIZE keeps native CPU use predictable on phones.
    }
    std::vector<float> run(const std::vector<float> &x, const Progress &progress) override {
        if (!model_)
            throw std::runtime_error("Separation model not loaded");
        int frames = static_cast<int>(x.size() / 2), padded = std::max(2, frames);
        Eigen::MatrixXf audio = Eigen::MatrixXf::Zero(2, padded);
        for (int i = 0; i < frames; ++i) {
            audio(0, i) = x[i * 2];
            audio(1, i) = x[i * 2 + 1];
        }
        auto stems = demucscpp::demucs_inference(*model_, audio, progress);
        if (stems.dimension(0) != 4 || stems.dimension(1) != 2 || stems.dimension(2) != padded)
            throw std::runtime_error("HTDemucs returned invalid tensor dimensions");
        std::vector<float> y(x.size());
        for (int i = 0; i < frames; ++i)
            for (int c = 0; c < 2; ++c)
                y[i * 2 + c] = stems(0, c, i) + stems(1, c, i) + stems(2, c, i);
        return y;
    }
};
} // namespace
std::unique_ptr<Backend> make_demucs_backend() { return std::make_unique<DemucsBackend>(); }
} // namespace vocal
