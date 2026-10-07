#include <android-base/file.h>
#include <android-base/parseint.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <gtest/gtest.h>
#include <inttypes.h>
#include <sched.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/utsname.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <optional>
#include <random>
#include <sstream>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {
constexpr int64_t kMiB = 1024 * 1024;

struct Options {
    std::string directory = "/data/local/tmp";
    int64_t cutoffMiB = 20;
    int64_t fileMiB = 128;
    int64_t rounds = 3;
    int64_t allocationMiB = 0;
} options;

enum class Stage { Baseline, Target, Recovery };
enum class Condition { Below, AtOrAbove, Crossed, Unknown };
constexpr const char* kStages[] = {"baseline", "target", "recovery"};
constexpr const char* kConditions[] = {"below", "at_or_above", "crossed", "unknown"};

void log(const char* format, ...) __attribute__((format(printf, 1, 2)));
void log(const char* format, ...) {
    std::string text;
    va_list args;
    va_start(args, format);
    android::base::StringAppendV(&text, format, args);
    va_end(args);
    fprintf(stderr, "%s\n", text.c_str());
    __android_log_write(ANDROID_LOG_INFO, "ColdFileReadBenchmark", text.c_str());
}

template <typename T>
void metric(const std::string& key, T value) {
    log("coldfile_%s=%s", key.c_str(), std::to_string(value).c_str());
}

// Log the scan thread's context: shell processes and visible apps can be scheduled differently.
// These reads are optional diagnostics, outside the timed scan and its resource counters.
void logThreadContext(Stage stage, bool warm) {
    int policy = sched_getscheduler(0);
    int policyError = policy < 0 ? errno : 0;
    sched_param priority{};
    int priorityError = sched_getparam(0, &priority) == 0 ? 0 : errno;

    // A nice value of -1 is valid, so errno distinguishes it from a failed read.
    errno = 0;
    int nice = getpriority(PRIO_PROCESS, 0);
    int niceError = errno;

    cpu_set_t affinity{};
    int affinityError = sched_getaffinity(0, sizeof(affinity), &affinity) == 0 ? 0 : errno;
    std::vector<int> cpus;
    if (!affinityError) {
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
            if (CPU_ISSET(cpu, &affinity)) {
                cpus.push_back(cpu);
            }
        }
    }

    std::string cgroups;
    int cgroupError =
            android::base::ReadFileToString("/proc/thread-self/cgroup", &cgroups) ? 0 : errno;
    std::replace(cgroups.begin(), cgroups.end(), '\n', ';');

    log("thread_context stage=%s cache=%s sched_policy=%d policy_errno=%d"
        " sched_priority=%d priority_errno=%d nice=%d nice_errno=%d"
        " allowed_cpus=%s affinity_errno=%d cgroups=%s cgroup_errno=%d",
        kStages[size_t(stage)], warm ? "warm" : "cold", policy, policyError,
        priorityError ? -1 : priority.sched_priority, priorityError, nice, niceError,
        android::base::Join(cpus, ',').c_str(), affinityError, cgroups.c_str(), cgroupError);
}

struct Memory {
    int64_t total = -1;
    int64_t available = -1;
    int64_t cached = -1;

    int64_t swapTotal = -1;
    int64_t swapFree = -1;
    bool valid = false;
};

Memory readMemory() {
    Memory info;
    std::string text;
    if (!android::base::ReadFileToString("/proc/meminfo", &text)) {
        return info;
    }

    std::istringstream lines(text);
    std::string line;
    char key[128];
    int64_t value;
    while (std::getline(lines, line)) {
        if (sscanf(line.c_str(), "%127[^:]: %" SCNd64, key, &value) != 2) {
            continue;
        }
        if (!strcmp(key, "MemTotal")) {
            info.total = value;
        }
        if (!strcmp(key, "MemAvailable")) {
            info.available = value;
        }
        if (!strcmp(key, "Cached")) {
            info.cached = value;
        }
        if (!strcmp(key, "SwapTotal")) {
            info.swapTotal = value;
        }
        if (!strcmp(key, "SwapFree")) {
            info.swapFree = value;
        }
    }

    info.valid = info.total > 0 && info.swapTotal >= 0 && info.swapFree >= 0 &&
            info.swapFree <= info.swapTotal;
    return info;
}

struct ReadCounter {
    int64_t bytes = -1;
    int error = 0;
};

ReadCounter readCounter() {
    ReadCounter counter;
    std::string text;
    if (!android::base::ReadFileToString("/proc/thread-self/io", &text)) {
        counter.error = errno;
        return counter;
    }

    std::istringstream lines(text);
    std::string line;
    while (std::getline(lines, line)) {
        if (sscanf(line.c_str(), "read_bytes: %" SCNd64, &counter.bytes) == 1) {
            break;
        }
    }

    counter.error = counter.bytes < 0 ? EPROTO : 0;
    return counter;
}

struct SwapWindow {
    Memory before, after;
    int64_t minimum = INT64_MAX, maximum = -1;
    int samples = 0;
    bool unknown = false;

    void observe(const Memory& info) {
        if (!info.valid) {
            unknown = true;
            return;
        }

        minimum = std::min(minimum, info.swapFree);
        maximum = std::max(maximum, info.swapFree);
        ++samples;
    }

    Condition condition() const {
        if (unknown || !samples) {
            return Condition::Unknown;
        }
        if (maximum < options.cutoffMiB * 1024) {
            return Condition::Below;
        }
        if (minimum >= options.cutoffMiB * 1024) {
            return Condition::AtOrAbove;
        }
        return Condition::Crossed;
    }
};

// Track the sampled swap range so observed cutoff crossings can be excluded from the comparison.
class SwapSampler {
public:
    SwapSampler()
          : mThread([this] {
                std::unique_lock<std::mutex> lock(mMutex);
                while (!mWake.wait_for(lock, std::chrono::milliseconds(100),
                                       [this] { return mStop; })) {
                    // Capture under the lock so a late read cannot be attributed to a subsequent
                    // scan.
                    if (mWindow) {
                        mWindow->observe(readMemory());
                    }
                }
            }) {}

    ~SwapSampler() {
        {
            std::lock_guard<std::mutex> lock(mMutex);
            mStop = true;
        }
        mWake.notify_one();
        mThread.join();
    }

    void begin() {
        std::lock_guard<std::mutex> lock(mMutex);
        mWindow.emplace();
        mWindow->before = readMemory();
        mWindow->observe(mWindow->before);
    }

    SwapWindow finish() {
        std::lock_guard<std::mutex> lock(mMutex);
        mWindow->after = readMemory();
        mWindow->observe(mWindow->after);
        SwapWindow result = *mWindow;
        mWindow.reset();
        return result;
    }

private:
    std::mutex mMutex;
    std::condition_variable mWake;
    bool mStop = false;
    std::optional<SwapWindow> mWindow;
    std::thread mThread;
};

struct Mapping {
    Mapping(size_t bytes, int fd)
          : bytes(bytes), address(mmap(nullptr, bytes, PROT_READ, MAP_PRIVATE, fd, 0)) {}

    ~Mapping() {
        if (address != MAP_FAILED) {
            munmap(address, bytes);
        }
    }

    const size_t bytes;
    void* const address;
};

// Touch one byte per page to measure file faults without timing a full buffer copy.
uint64_t touch(const Mapping& mapping, size_t pageSize) {
    const auto* data = static_cast<volatile const unsigned char*>(mapping.address);
    uint64_t checksum = 0;
    for (size_t i = 0; i < mapping.bytes; i += pageSize) {
        checksum += data[i];
    }
    return checksum;
}

double median(std::vector<double> values) {
    if (values.empty()) {
        return -1;
    }

    std::sort(values.begin(), values.end());
    size_t middle = values.size() / 2;
    return values.size() % 2 ? values[middle] : (values[middle - 1] + values[middle]) / 2;
}

struct Sample {
    Stage stage;
    bool warm = false;
    Condition condition = Condition::Unknown;
    int64_t durationNs = 0;
    int64_t cpuUs = 0;
    int64_t voluntarySwitches = 0;
    int64_t involuntarySwitches = 0;
    int64_t major = 0;
    int64_t readBytes = -1;
    SwapWindow window{};
    const char* exclusion = nullptr;
};

struct Results {
    std::vector<double> times;
    std::vector<double> cpuTimes;
    std::vector<double> voluntarySwitches;
    std::vector<double> involuntarySwitches;
    std::vector<double> faults;
    int64_t readMin = INT64_MAX;
    int64_t swapMin = INT64_MAX, swapMax = -1;

    void add(const Sample& sample) {
        times.push_back(sample.durationNs / 1e6);
        cpuTimes.push_back(sample.cpuUs / 1e3);
        voluntarySwitches.push_back(sample.voluntarySwitches);
        involuntarySwitches.push_back(sample.involuntarySwitches);
        faults.push_back(sample.major);
        readMin = std::min(readMin, sample.readBytes);
        swapMin = std::min(swapMin, sample.window.minimum);
        swapMax = std::max(swapMax, sample.window.maximum);
    }

    double report(const std::string& name) const {
        double time = median(times);
        metric(name + "_valid_samples", times.size());
        metric(name + "_median_ms", time);
        metric(name + "_cpu_median_ms", median(cpuTimes));
        metric(name + "_voluntary_context_switches_median", median(voluntarySwitches));
        metric(name + "_involuntary_context_switches_median", median(involuntarySwitches));
        metric(name + "_major_faults_median", median(faults));
        metric(name + "_read_bytes_min", times.empty() ? int64_t(-1) : readMin);
        metric(name + "_swap_free_min_kib", times.empty() ? int64_t(-1) : swapMin);
        metric(name + "_swap_free_max_kib", swapMax);
        return time;
    }
};

class ColdFileReadTest : public ::testing::Test {
protected:
    void SetUp() override {
        long page = sysconf(_SC_PAGESIZE);
        ASSERT_GT(page, 0);
        ASSERT_EQ(0, kMiB % page);
        mPage = page;

        Memory initial = readMemory();
        ASSERT_TRUE(initial.valid) << "Cannot read memory and swap counters";
        ASSERT_GT(initial.swapTotal, 0) << "Swap must be enabled";

        // Bound the payload by RAM plus swap, leaving some RAM headroom for the test and system.
        int64_t cap = std::min(int64_t(32768),
                               initial.total / 1024 * 80 / 100 + initial.swapTotal / 1024);
        mBudgetMiB = options.allocationMiB ? options.allocationMiB
                                           : std::min(cap, initial.swapTotal / 1024 + 256);
        ASSERT_LE(mBudgetMiB, cap) << "Allocation budget exceeds RAM and swap cap";

        metric("allocation_budget_mib", mBudgetMiB);
        metric("swap_cutoff_mib", options.cutoffMiB);
        metric("file_size_bytes", options.fileMiB * kMiB);
        metric("page_size_bytes", mPage);

        utsname kernel{};
        ASSERT_EQ(0, uname(&kernel));
        log("initial kernel=%s available_kib=%" PRId64 " cached_kib=%" PRId64
            " swap_total_kib=%" PRId64 " swap_free_kib=%" PRId64 " setup=anonymous_madv_pageout",
            kernel.release, initial.available, initial.cached, initial.swapTotal, initial.swapFree);

        // These policy nodes are Pixel-specific; root reads are optional diagnostics.
        for (const char* name : {"async_readahead_adj_enable", "swap_free_threshold_mb"}) {
            std::string path = std::string("/sys/kernel/vendor_mm/filemap/") + name;
            std::string command =
                    "/system/xbin/su 0 /system/bin/timeout -s KILL 5 /system/bin/cat " + path +
                    " 2>/dev/null";
            FILE* file = popen(command.c_str(), "re");
            if (!file) {
                log("policy name=%s read_errno=%d", name, errno);
                continue;
            }

            int64_t value;
            bool parsed = fscanf(file, "%" SCNd64, &value) == 1;
            int status = pclose(file);
            if (parsed && status == 0) {
                log("policy name=%s value=%" PRId64, name, value);
            } else {
                log("policy name=%s unavailable=1 wait_status=%d", name, status);
            }
        }
    }

    void TearDown() override {
        Release();

        Results above, below, warm;
        int excluded = 0;
        for (const auto& sample : mSamples) {
            if (sample.exclusion) {
                ++excluded;
                continue;
            }
            if (sample.warm) {
                warm.add(sample);
            } else {
                (sample.condition == Condition::Below ? below : above).add(sample);
            }
        }

        double aboveMs = above.report("above_cold"), belowMs = below.report("below_cold");
        warm.report("warm");
        metric("below_over_above_ratio", aboveMs > 0 && belowMs >= 0 ? belowMs / aboveMs : -1);
        metric("comparison_available", aboveMs > 0 && belowMs >= 0);
        metric("excluded_samples", excluded);
        metric("target_valid_below_samples", TargetBelow());
        metric("allocated_bytes_peak", mPeakBytes);
        metric("workload_valid", !HasFailure() && TargetBelow() == options.rounds);
    }

    // Write random file data through direct I/O so setup does not warm the file cache.
    void Prepare(const char* path, uint64_t* checksum) {
        android::base::unique_fd fd(open(path, O_WRONLY | O_DIRECT | O_CLOEXEC));
        ASSERT_GE(fd.get(), 0) << strerror(errno);

        void* memory;
        ASSERT_EQ(0, posix_memalign(&memory, mPage, kMiB));
        std::unique_ptr<void, decltype(&free)> buffer(memory, &free);
        auto* data = static_cast<unsigned char*>(memory);
        std::mt19937_64 random(0x17c3a2026ULL);

        *checksum = 0;
        for (int64_t done = 0; done < options.fileMiB * kMiB; done += kMiB) {
            for (size_t i = 0; i < size_t(kMiB); i += 8) {
                uint64_t value = random();
                memcpy(data + i, &value, 8);
            }
            for (size_t i = 0; i < size_t(kMiB); i += mPage) {
                *checksum += data[i];
            }

            size_t written = 0;
            while (written < size_t(kMiB)) {
                ssize_t count = write(fd.get(), data + written, kMiB - written);
                if (count < 0 && errno == EINTR) {
                    continue;
                }
                ASSERT_GT(count, 0) << strerror(errno);
                ASSERT_EQ(0u, size_t(count) % mPage) << "Unaligned direct write";
                written += count;
            }
        }

        ASSERT_EQ(0, fdatasync(fd.get())) << strerror(errno);
    }

    // Fill swap with our own anonymous allocations and hold them during the target scans.
    // Request pageout so reaching the swap cutoff does not rely only on exhausting RAM.
    void Pressure() {
        Memory info = readMemory();
        ASSERT_TRUE(info.valid);

        while (info.swapFree >= options.cutoffMiB * 1024 && mAllocatedBytes < mBudgetMiB * kMiB) {
            size_t bytes = std::min(int64_t(16 * kMiB), mBudgetMiB * kMiB - mAllocatedBytes);
            void* address = mmap(nullptr, bytes, PROT_READ | PROT_WRITE,
                                 MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
            ASSERT_NE(MAP_FAILED, address) << strerror(errno);

            mBlocks.emplace_back(address, bytes);
            mAllocatedBytes += bytes;
            mPeakBytes = std::max(mPeakBytes, mAllocatedBytes);

            // Keep half of each page random so zram still needs space for the allocations.
            auto* data = static_cast<unsigned char*>(address);
            for (size_t offset = 0; offset < bytes; offset += mPage) {
                memset(data + offset, 0x5a, mPage);
                for (size_t i = 0; i < mPage / 2; i += 8) {
                    uint64_t value = mAllocationRandom();
                    memcpy(data + offset + i, &value, 8);
                }
            }

            ASSERT_EQ(0, madvise(address, bytes, MADV_PAGEOUT)) << strerror(errno);
            std::this_thread::sleep_for(std::chrono::milliseconds(250));
            info = readMemory();
            ASSERT_TRUE(info.valid);
            log("allocation bytes=%" PRId64 " swap_free_kib=%" PRId64, mAllocatedBytes,
                info.swapFree);
        }

        // PAGEOUT is advisory. Give idle mappings one more request at the allocation limit.
        if (info.swapFree >= options.cutoffMiB * 1024) {
            for (auto block : mBlocks) {
                ASSERT_EQ(0, madvise(block.first, block.second, MADV_PAGEOUT));
            }
            std::this_thread::sleep_for(std::chrono::seconds(2));
            info = readMemory();
            ASSERT_TRUE(info.valid);
        }

        ASSERT_LT(info.swapFree, options.cutoffMiB * 1024)
                << "Allocation limit reached without swap target";
        log("hold allocated_bytes=%" PRId64 " swap_free_kib=%" PRId64, mAllocatedBytes,
            info.swapFree);
    }

    // Drop the held allocations before recovery scans and on any test failure.
    void Release() {
        for (auto block : mBlocks) {
            EXPECT_EQ(0, munmap(block.first, block.second));
        }
        mBlocks.clear();
        mAllocatedBytes = 0;
    }

    // Measure file fault latency for a cold or warm scan under the sampled swap condition.
    void RecordScan(const char* path, uint64_t expected, Stage stage, bool warm) {
        Sample sample{stage, warm};
        size_t bytes = options.fileMiB * kMiB, pages = bytes / mPage;
        std::vector<unsigned char> residency(pages, 0);
        android::base::unique_fd fd(open(path, O_RDONLY | O_CLOEXEC));
        ASSERT_GE(fd.get(), 0) << strerror(errno);

        mSampler.begin();
        ASSERT_EQ(0, posix_fadvise(fd.get(), 0, bytes, POSIX_FADV_DONTNEED));
        if (warm) {
            // Warm the file, then unmap it so the timed scan uses fresh page table entries too.
            Mapping prime(bytes, fd.get());
            ASSERT_NE(MAP_FAILED, prime.address) << strerror(errno);
            ASSERT_EQ(expected, touch(prime, mPage));
        }

        Mapping mapping(bytes, fd.get());
        ASSERT_NE(MAP_FAILED, mapping.address) << strerror(errno);
        ASSERT_EQ(0, madvise(mapping.address, bytes, MADV_NORMAL));
        ASSERT_EQ(0, mincore(mapping.address, bytes, residency.data())) << strerror(errno);
        int64_t residentBefore = std::count_if(residency.begin(), residency.end(),
                                               [](auto value) { return value & 1; });

        logThreadContext(stage, warm);

        // Use this thread's counters so the swap sampler does not affect the scan's accounting.
        ReadCounter ioBefore = readCounter();
        ASSERT_EQ(0, ioBefore.error);
        rusage before{}, after{};
        ASSERT_EQ(0, getrusage(RUSAGE_THREAD, &before));

        auto start = std::chrono::steady_clock::now();
        uint64_t checksum = touch(mapping, mPage);
        sample.durationNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
                                    std::chrono::steady_clock::now() - start)
                                    .count();

        ASSERT_EQ(0, getrusage(RUSAGE_THREAD, &after));
        ReadCounter ioAfter = readCounter();
        ASSERT_EQ(0, ioAfter.error);
        ASSERT_GE(ioAfter.bytes, ioBefore.bytes);
        ASSERT_EQ(expected, checksum);

        auto cpuTimeUs = [](const rusage& usage) {
            return int64_t(usage.ru_utime.tv_sec + usage.ru_stime.tv_sec) * 1000000 +
                    usage.ru_utime.tv_usec + usage.ru_stime.tv_usec;
        };
        sample.cpuUs = cpuTimeUs(after) - cpuTimeUs(before);
        sample.voluntarySwitches = after.ru_nvcsw - before.ru_nvcsw;
        sample.involuntarySwitches = after.ru_nivcsw - before.ru_nivcsw;
        sample.major = after.ru_majflt - before.ru_majflt;
        sample.readBytes = ioAfter.bytes - ioBefore.bytes;
        sample.window = mSampler.finish();
        sample.condition = sample.window.condition();

        // Zero page cache residency alone is insufficient: a secondary cache can serve evicted
        // pages. Cold samples must also account for reading the full file.
        if (sample.condition == Condition::Crossed || sample.condition == Condition::Unknown) {
            sample.exclusion = kConditions[size_t(sample.condition)];
        } else if (!warm && residentBefore != 0) {
            sample.exclusion = "initial_residency_nonzero";
        } else if (warm && residentBefore != int64_t(pages)) {
            sample.exclusion = "warm_residency_incomplete";
        } else if (!warm && sample.readBytes < int64_t(bytes)) {
            sample.exclusion = "incomplete_file_read_accounting";
        }

        int64_t startNs =
                std::chrono::duration_cast<std::chrono::nanoseconds>(start.time_since_epoch())
                        .count();
        log("scan stage=%s cache=%s monotonic_ns=%" PRId64 " touch_ms=%.6f major_faults=%" PRId64
            " minor_faults=%ld read_bytes=%" PRId64 " resident_before_pages=%" PRId64
            " touched_pages=%zu checksum=%" PRIu64 " swap_condition=%s swap_min_kib=%" PRId64
            " swap_max_kib=%" PRId64 " window_samples=%d available_before_kib=%" PRId64
            " available_after_kib=%" PRId64 " thread_cpu_ms=%.6f"
            " voluntary_context_switches=%" PRId64 " involuntary_context_switches=%" PRId64
            " exclusion=%s",
            kStages[size_t(stage)], warm ? "warm" : "cold", startNs, sample.durationNs / 1e6,
            sample.major, after.ru_minflt - before.ru_minflt, sample.readBytes, residentBefore,
            pages, checksum, kConditions[size_t(sample.condition)],
            sample.window.samples ? sample.window.minimum : int64_t(-1), sample.window.maximum,
            sample.window.samples, sample.window.before.available, sample.window.after.available,
            sample.cpuUs / 1e3, sample.voluntarySwitches, sample.involuntarySwitches,
            sample.exclusion ? sample.exclusion : "none");
        mSamples.push_back(sample);
    }

    // Use a fresh inode for each pair to avoid reusing file data held in a secondary cache.
    // Prepare it before adjusting pressure so setup does not disturb the swap target.
    void Pair(Stage stage, int round) {
        TemporaryFile file(options.directory);
        ASSERT_GE(file.fd, 0) << strerror(errno);

        uint64_t checksum;
        ASSERT_NO_FATAL_FAILURE(Prepare(file.path, &checksum));
        log("corpus stage=%s round=%d path=%s bytes=%" PRId64 " direct_requested=1",
            kStages[size_t(stage)], round, file.path, options.fileMiB * kMiB);

        if (stage == Stage::Target) {
            ASSERT_NO_FATAL_FAILURE(Pressure());
        }

        for (bool warm : {false, true}) {
            ASSERT_NO_FATAL_FAILURE(RecordScan(file.path, checksum, stage, warm));
        }
    }

    int TargetBelow() const {
        return std::count_if(mSamples.begin(), mSamples.end(), [](const Sample& sample) {
            return sample.stage == Stage::Target && !sample.warm && !sample.exclusion &&
                    sample.condition == Condition::Below;
        });
    }

    size_t mPage = 0;
    int64_t mBudgetMiB = 0;
    int64_t mAllocatedBytes = 0;
    int64_t mPeakBytes = 0;
    std::mt19937_64 mAllocationRandom{0x17c3a2026ULL};

    SwapSampler mSampler;
    std::vector<std::pair<void*, size_t>> mBlocks;
    std::vector<Sample> mSamples;
};

TEST_F(ColdFileReadTest, BelowSwapCutoff) {
    for (int round = 0; round < options.rounds; ++round) {
        ASSERT_NO_FATAL_FAILURE(Pair(Stage::Baseline, round));
    }

    // Allow extra attempts because a scan that crosses the cutoff is excluded from the comparison.
    for (int attempt = 0; attempt < options.rounds * 3 && TargetBelow() < options.rounds;
         ++attempt) {
        ASSERT_NO_FATAL_FAILURE(Pair(Stage::Target, attempt));
    }
    ASSERT_EQ(options.rounds, TargetBelow()) << "Insufficient valid scans below swap cutoff";

    Release();
    ASSERT_FALSE(HasFailure());
    log("release allocated_bytes=0");
    std::this_thread::sleep_for(std::chrono::seconds(2));

    for (int round = 0; round < options.rounds; ++round) {
        ASSERT_NO_FATAL_FAILURE(Pair(Stage::Recovery, round));
    }
}

bool parseOptions(int argc, char** argv) {
    for (int i = 1; i < argc; i += 2) {
        if (i + 1 == argc) {
            return false;
        }

        std::string name = argv[i];
        if (name == "--work-dir") {
            options.directory = argv[i + 1];
            continue;
        }

        int64_t value;
        if (!android::base::ParseInt(argv[i + 1], &value, int64_t(0))) {
            return false;
        }

        if (name == "--swap-cutoff-mib") {
            options.cutoffMiB = value;
        } else if (name == "--file-mib") {
            options.fileMiB = value;
        } else if (name == "--rounds") {
            options.rounds = value;
        } else if (name == "--max-allocation-mib") {
            options.allocationMiB = value;
        } else {
            return false;
        }
    }

    return options.cutoffMiB >= 1 && options.cutoffMiB <= 1024 && options.fileMiB >= 16 &&
            options.fileMiB <= 512 && options.rounds >= 1 && options.rounds <= 20 &&
            options.allocationMiB <= 32768;
}
} // namespace

int main(int argc, char** argv) {
    ::testing::InitGoogleTest(&argc, argv);
    if (!parseOptions(argc, argv)) {
        fprintf(stderr, "Invalid cold file read options\n");
        return 2;
    }
    return RUN_ALL_TESTS();
}
