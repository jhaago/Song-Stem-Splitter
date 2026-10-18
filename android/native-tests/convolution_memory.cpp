#include "conv.hpp"
#include <cmath>
#include <iostream>
#include <sys/resource.h>
int main() {
    // A realistic decoder operation must not materialize the full sparse expansion.
    Eigen::Tensor3dXf x(48, 22000, 1);
    x.setConstant(.01f);
    Eigen::Tensor4dXf w(48, 8, 8, 1);
    w.setConstant(.001f);
    Eigen::Tensor1dXf b(8);
    b.setZero();
    auto y = demucscpp::conv2d_tr<48, 8, 8, 1, 4, 1, 0, 0, 1, 1>(x, w, b);
    if (y.dimension(0) != 8 || y.dimension(1) != 88004 || y.dimension(2) != 1)
        return 2;
    for (Eigen::Index i = 0; i < y.size(); ++i)
        if (!std::isfinite(y.data()[i]))
            return 3;
    rusage usage{};
    getrusage(RUSAGE_SELF, &usage);
    std::cout << "decoder peak RSS KiB: " << usage.ru_maxrss << '\n';
    if (usage.ru_maxrss > 96 * 1024) {
        std::cerr << "Sparse decoder workspace exceeded 96 MiB\n";
        return 1;
    }
    return 0;
}
