#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cerrno>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#if defined(__ANDROID__)
#include <sched.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

#include "llama.h"

namespace {

constexpr const char * TAG = "MarfaLlamaNative";
constexpr int BATCH_SIZE = 512;

struct NativeEngine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    int threads = 0;
    int context_size = 0;
    int batch_size = 0;
    std::string affinity_info;
    std::string system_info;
};

void logInfo(const std::string & message) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", message.c_str());
}

void logError(const std::string & message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
}

std::string affinityMaskDescription() {
#if defined(__ANDROID__)
    cpu_set_t current;
    CPU_ZERO(&current);
    if (sched_getaffinity(0, sizeof(current), &current) != 0) {
        return "get_failed:" + std::string(std::strerror(errno));
    }
    std::string out;
    for (int cpu = 0; cpu < 32; ++cpu) {
        if (!CPU_ISSET(cpu, &current)) continue;
        if (!out.empty()) out += ",";
        out += std::to_string(cpu);
    }
    return out.empty() ? "none" : out;
#else
    return "non-Android";
#endif
}

bool pinToPerformanceCores(int & selectedThreads, std::string & description) {
#if defined(__ANDROID__)
    struct CoreInfo { int cpu; long long freq; };
    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    if (sched_getaffinity(0, sizeof(allowed), &allowed) != 0) {
        description = "affinity=get_failed:" + std::string(std::strerror(errno));
        return false;
    }
    std::vector<CoreInfo> cores;
    for (int cpu = 0; cpu < 32; ++cpu) {
        if (!CPU_ISSET(cpu, &allowed)) continue;
        std::ifstream in("/sys/devices/system/cpu/cpu" + std::to_string(cpu) +
                         "/cpufreq/cpuinfo_max_freq");
        long long freq = 0;
        if (in) in >> freq;
        if (freq > 0) cores.push_back({cpu, freq});
    }
    if (cores.size() < 2) {
        description = "affinity=allowed_cores_lt2";
        return false;
    }
    std::sort(cores.begin(), cores.end(), [](const CoreInfo & a, const CoreInfo & b) {
        if (a.freq != b.freq) return a.freq > b.freq;
        return a.cpu > b.cpu;
    });
    const long long bestFreq = cores.front().freq;
    std::vector<int> best;
    for (const auto & core : cores) if (core.freq == bestFreq) best.push_back(core.cpu);
    if (best.size() < 2) best = {cores[0].cpu, cores[1].cpu};

    cpu_set_t selected;
    CPU_ZERO(&selected);
    for (const int cpu : best) CPU_SET(cpu, &selected);
    if (syscall(SYS_sched_setaffinity, 0, sizeof(selected), &selected) != 0) {
        description = "affinity=failed:" + std::string(std::strerror(errno)) + ",allowed=";
        for (const auto & core : cores) description += std::to_string(core.cpu) + ",";
        return false;
    }

    cpu_set_t applied;
    CPU_ZERO(&applied);
    if (sched_getaffinity(0, sizeof(applied), &applied) != 0) {
        description = "affinity=applied_get_failed:" + std::string(std::strerror(errno));
        return false;
    }
    const std::string afterMask = affinityMaskDescription();
    selectedThreads = std::min(selectedThreads, static_cast<int>(best.size()));
    description = "affinity=big[";
    for (size_t i = 0; i < best.size(); ++i) {
        if (i) description += ",";
        description += std::to_string(best[i]);
    }
    description += "],freq=" + std::to_string(bestFreq) +
                   ",threads=" + std::to_string(selectedThreads) +
                   ",mask=" + afterMask;
    return true;
#else
    description = "affinity=non-Android";
    return false;
#endif
}

std::string jsonEscape(const std::string & value) {
    std::ostringstream out;
    for (unsigned char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            default:
                if (c < 0x20) {
                    const char * hex = "0123456789abcdef";
                    out << "\\u00" << hex[(c >> 4) & 0x0f] << hex[c & 0x0f];
                } else {
                    out << static_cast<char>(c);
                }
        }
    }
    return out.str();
}

std::string makeFallbackPrompt(const std::string & systemPrompt, const std::string & userPrompt) {
    std::string prompt;
    if (!systemPrompt.empty()) {
        prompt += "System: ";
        prompt += systemPrompt;
        prompt += "\n";
    }
    prompt += "User: ";
    prompt += userPrompt;
    prompt += "\nAssistant:";
    return prompt;
}

bool formatChat(
        const llama_model * model,
        const std::string & systemPrompt,
        const std::string & userPrompt,
        std::string & formatted) {
    const char * tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl == nullptr || *tmpl == '\0') {
        formatted = makeFallbackPrompt(systemPrompt, userPrompt);
        return true;
    }

    llama_chat_message messages[2];
    size_t n_messages = 0;

    if (!systemPrompt.empty()) {
        messages[n_messages++] = {"system", systemPrompt.c_str()};
    }
    messages[n_messages++] = {"user", userPrompt.c_str()};

    const int required = llama_chat_apply_template(
            tmpl,
            messages,
            n_messages,
            true,
            nullptr,
            0);
    if (required <= 0) {
        logError("llama_chat_apply_template returned invalid size");
        return false;
    }

    formatted.resize(static_cast<size_t>(required));
    const int written = llama_chat_apply_template(
            tmpl,
            messages,
            n_messages,
            true,
            formatted.data(),
            required);
    if (written < 0) {
        logError("llama_chat_apply_template failed");
        return false;
    }

    formatted.resize(static_cast<size_t>(written));
    return true;
}

bool tokenizePrompt(
        const llama_vocab * vocab,
        const std::string & prompt,
        std::vector<llama_token> & tokens) {
    const int needed = llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            nullptr,
            0,
            false,
            true);

    if (needed < 0) {
        tokens.resize(static_cast<size_t>(-needed));
    } else {
        tokens.resize(static_cast<size_t>(needed));
    }

    const int actual = llama_tokenize(
            vocab,
            prompt.c_str(),
            static_cast<int32_t>(prompt.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            false,
            true);

    if (actual < 0) {
        return false;
    }

    tokens.resize(static_cast<size_t>(actual));
    return true;
}

bool decodePrompt(NativeEngine * engine, const std::vector<llama_token> & tokens) {
    if (tokens.empty()) {
        return false;
    }

    llama_batch batch = llama_batch_init(
            std::min<int>(static_cast<int>(tokens.size()), BATCH_SIZE),
            0,
            1);

    if (batch.token == nullptr) {
        return false;
    }

    int processed = 0;
    while (processed < static_cast<int>(tokens.size())) {
        const int count = std::min(
                BATCH_SIZE,
                static_cast<int>(tokens.size()) - processed);

        batch.n_tokens = count;
        for (int i = 0; i < count; ++i) {
            batch.token[i] = tokens[processed + i];
            batch.pos[i] = processed + i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i == count - 1) ? 1 : 0;
        }

        const int rc = llama_decode(engine->context, batch);
        if (rc != 0) {
            llama_batch_free(batch);
            return false;
        }

        processed += count;
    }

    llama_batch_free(batch);
    return true;
}

bool decodeOne(NativeEngine * engine, llama_token token, llama_pos position) {
    llama_batch batch = llama_batch_init(1, 0, 1);
    if (batch.token == nullptr) {
        return false;
    }

    batch.n_tokens = 1;
    batch.token[0] = token;
    batch.pos[0] = position;
    batch.n_seq_id[0] = 1;
    batch.seq_id[0][0] = 0;
    batch.logits[0] = 1;

    const int rc = llama_decode(engine->context, batch);
    llama_batch_free(batch);
    return rc == 0;
}

std::string tokenToPiece(const llama_vocab * vocab, llama_token token) {
    char buffer[4096];
    const int n = llama_token_to_piece(
            vocab,
            token,
            buffer,
            static_cast<int32_t>(sizeof(buffer)),
            0,
            true);
    if (n <= 0) {
        return {};
    }
    return std::string(buffer, static_cast<size_t>(n));
}

} // namespace

extern "C"
JNIEXPORT jlong JNICALL
Java_com_yasn198020_aicontrol_MarfaLlamaNative_nativeLoadModel(
        JNIEnv * env,
        jobject,
        jstring jModelPath,
        jint threads,
        jint contextSize) {
    const char * modelPathChars = env->GetStringUTFChars(jModelPath, nullptr);
    if (modelPathChars == nullptr) {
        return 0;
    }

    const std::string modelPath(modelPathChars);
    env->ReleaseStringUTFChars(jModelPath, modelPathChars);

    llama_backend_init();

    const std::string systemInfo = llama_print_system_info();
    logInfo("System info: " + systemInfo);

    int effectiveThreads = std::max(1, static_cast<int>(threads));
    std::string affinityInfo;
    pinToPerformanceCores(effectiveThreads, affinityInfo);
    logInfo("CPU tuning: " + affinityInfo);

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;

    llama_model * model = llama_model_load_from_file(
            modelPath.c_str(),
            modelParams);

    if (model == nullptr) {
        logError("Unable to load GGUF model: " + modelPath);
        return 0;
    }

    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = static_cast<uint32_t>(std::max(128, static_cast<int>(contextSize)));
    contextParams.n_batch = static_cast<uint32_t>(std::max(32, std::min(BATCH_SIZE, static_cast<int>(contextParams.n_ctx))));
    contextParams.n_ubatch = contextParams.n_batch;
    contextParams.n_threads = effectiveThreads;
    contextParams.n_threads_batch = effectiveThreads;
    contextParams.no_perf = false;

    llama_context * context = llama_init_from_model(model, contextParams);
    if (context == nullptr) {
        logError("Unable to create llama context");
        llama_model_free(model);
        return 0;
    }

    auto * engine = new NativeEngine;
    engine->model = model;
    engine->context = context;
    engine->threads = contextParams.n_threads;
    engine->context_size = static_cast<int>(contextParams.n_ctx);
    engine->batch_size = static_cast<int>(contextParams.n_batch);
    engine->affinity_info = affinityInfo;
    engine->system_info = systemInfo;

    const std::string info =
            "Native llama.cpp loaded: version=" + std::string(llama_version()) +
            ", threads=" + std::to_string(engine->threads) +
            ", context=" + std::to_string(engine->context_size) +
            ", batch=" + std::to_string(engine->batch_size) +
            ", " + affinityInfo;
    logInfo(info);

    return reinterpret_cast<jlong>(engine);
}


extern "C"
JNIEXPORT jstring JNICALL
Java_com_yasn198020_aicontrol_MarfaLlamaNative_nativeBenchmarkPrompt(
        JNIEnv * env,
        jobject,
        jlong handle,
        jstring jPrompt,
        jstring jSystemPrompt) {
    auto * engine = reinterpret_cast<NativeEngine *>(handle);
    if (engine == nullptr || engine->model == nullptr || engine->context == nullptr) {
        return env->NewStringUTF("{\"error\":\"invalid native handle\"}");
    }

    const char * promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    const char * systemChars = env->GetStringUTFChars(jSystemPrompt, nullptr);
    if (promptChars == nullptr || systemChars == nullptr) {
        if (promptChars) env->ReleaseStringUTFChars(jPrompt, promptChars);
        if (systemChars) env->ReleaseStringUTFChars(jSystemPrompt, systemChars);
        return env->NewStringUTF("{\"error\":\"JNI string conversion failed\"}");
    }
    const std::string userPrompt(promptChars);
    const std::string systemPrompt(systemChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);
    env->ReleaseStringUTFChars(jSystemPrompt, systemChars);

    std::string formatted;
    if (!formatChat(engine->model, systemPrompt, userPrompt, formatted)) {
        return env->NewStringUTF("{\"error\":\"chat template failed\"}");
    }

    const llama_vocab * vocab = llama_model_get_vocab(engine->model);
    std::vector<llama_token> tokens;
    if (!tokenizePrompt(vocab, formatted, tokens)) {
        return env->NewStringUTF("{\"error\":\"tokenization failed\"}");
    }

    const int batchSizes[] = {1, 2, 4, 8, 16, 32, 35};
    std::ostringstream result;
    result.setf(std::ios::fixed);
    result.precision(2);
    result << "{\"promptTokens\":" << tokens.size() << ",\"results\":[";

    for (size_t bi = 0; bi < sizeof(batchSizes) / sizeof(batchSizes[0]); ++bi) {
        const int batchSize = std::min(batchSizes[bi], static_cast<int>(tokens.size()));
        if (batchSize <= 0) continue;

        llama_memory_clear(llama_get_memory(engine->context), true);
        const auto start = std::chrono::steady_clock::now();

        int processed = 0;
        bool ok = true;
        while (processed < static_cast<int>(tokens.size())) {
            const int count = std::min(batchSize, static_cast<int>(tokens.size()) - processed);
            llama_batch batch = llama_batch_init(count, 0, 1);
            if (batch.token == nullptr) {
                ok = false;
                break;
            }
            batch.n_tokens = count;
            for (int i = 0; i < count; ++i) {
                batch.token[i] = tokens[processed + i];
                batch.pos[i] = processed + i;
                batch.n_seq_id[i] = 1;
                batch.seq_id[i][0] = 0;
                batch.logits[i] = (processed + i == static_cast<int>(tokens.size()) - 1) ? 1 : 0;
            }
            if (llama_decode(engine->context, batch) != 0) ok = false;
            llama_batch_free(batch);
            if (!ok) break;
            processed += count;
        }

        const auto end = std::chrono::steady_clock::now();
        const double ms = std::chrono::duration_cast<std::chrono::duration<double, std::milli>>(end - start).count();

        if (bi) result << ",";
        result << "{\"batch\":" << batchSize
               << ",\"ok\":" << (ok ? "true" : "false")
               << ",\"ms\":" << ms
               << ",\"tokPerSec\":" << (ms > 0.0 ? tokens.size() * 1000.0 / ms : 0.0)
               << "}";
        logInfo("Prompt benchmark batch=" + std::to_string(batchSize) +
                " ms=" + std::to_string(ms));
    }

    result << "]}";
    return env->NewStringUTF(result.str().c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_yasn198020_aicontrol_MarfaLlamaNative_nativeGenerate(
        JNIEnv * env,
        jobject,
        jlong handle,
        jstring jPrompt,
        jstring jSystemPrompt,
        jint maxTokens) {
    auto * engine = reinterpret_cast<NativeEngine *>(handle);
    if (engine == nullptr || engine->model == nullptr || engine->context == nullptr) {
        return env->NewStringUTF("{\"error\":\"invalid native handle\"}");
    }

    const char * promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    const char * systemChars = env->GetStringUTFChars(jSystemPrompt, nullptr);
    if (promptChars == nullptr || systemChars == nullptr) {
        if (promptChars) env->ReleaseStringUTFChars(jPrompt, promptChars);
        if (systemChars) env->ReleaseStringUTFChars(jSystemPrompt, systemChars);
        return env->NewStringUTF("{\"error\":\"JNI string conversion failed\"}");
    }

    const std::string userPrompt(promptChars);
    const std::string systemPrompt(systemChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);
    env->ReleaseStringUTFChars(jSystemPrompt, systemChars);

    const auto tStart = std::chrono::steady_clock::now();
    std::string formatted;
    if (!formatChat(engine->model, systemPrompt, userPrompt, formatted)) {
        return env->NewStringUTF("{\"error\":\"chat template failed\"}");
    }

    const auto tFormatted = std::chrono::steady_clock::now();
    const size_t formattedChars = formatted.size();
    const llama_vocab * vocab = llama_model_get_vocab(engine->model);
    std::vector<llama_token> promptTokens;
    if (!tokenizePrompt(vocab, formatted, promptTokens)) {
        return env->NewStringUTF("{\"error\":\"tokenization failed\"}");
    }

    const auto tTokenized = std::chrono::steady_clock::now();
    const size_t promptChars = userPrompt.size();
    const size_t systemChars = systemPrompt.size();
    const int maxNewTokens = std::max(1, static_cast<int>(maxTokens));
    const int requiredContext = static_cast<int>(promptTokens.size()) + maxNewTokens + 4;
    if (requiredContext > engine->context_size) {
        std::ostringstream error;
        error << "{\"error\":\"prompt too long: "
              << promptTokens.size()
              << " tokens + "
              << maxNewTokens
              << " output tokens exceeds context "
              << engine->context_size
              << "\"}";
        return env->NewStringUTF(error.str().c_str());
    }

    llama_memory_clear(llama_get_memory(engine->context), true);

    const auto t0 = std::chrono::steady_clock::now();
    if (!decodePrompt(engine, promptTokens)) {
        return env->NewStringUTF("{\"error\":\"prompt decode failed\"}");
    }

    const auto tPrompt = std::chrono::steady_clock::now();
    const double promptSeconds = std::chrono::duration_cast<std::chrono::duration<double>>(tPrompt - t0).count();

    auto samplerParams = llama_sampler_chain_default_params();
    samplerParams.no_perf = false;
    llama_sampler * sampler = llama_sampler_chain_init(samplerParams);
    if (sampler == nullptr) {
        return env->NewStringUTF("{\"error\":\"sampler init failed\"}");
    }

    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string output;
    int generated = 0;

    while (generated < maxNewTokens) {
        const llama_token token = llama_sampler_sample(sampler, engine->context, -1);
        llama_sampler_accept(sampler, token);

        if (llama_vocab_is_eog(vocab, token)) {
            break;
        }

        output += tokenToPiece(vocab, token);
        ++generated;

        if (!decodeOne(engine, token, static_cast<llama_pos>(promptTokens.size() + generated - 1))) {
            llama_sampler_free(sampler);
            return env->NewStringUTF("{\"error\":\"decode generated token failed\"}");
        }
    }

    const auto t1 = std::chrono::steady_clock::now();
    const double seconds =
            std::chrono::duration_cast<std::chrono::duration<double>>(t1 - t0).count();

    const double tokensPerSecond =
            seconds > 0.0 ? static_cast<double>(generated) / seconds : 0.0;
    const double generationSeconds =
            seconds - promptSeconds;
    const double generationTokensPerSecond =
            generationSeconds > 0.0 ? static_cast<double>(generated) / generationSeconds : 0.0;
    const double promptTokensPerSecond =
            promptSeconds > 0.0 ? static_cast<double>(promptTokens.size()) / promptSeconds : 0.0;

    llama_sampler_free(sampler);

    std::ostringstream result;
    result.setf(std::ios::fixed);
    result.precision(4);
    result
        << "{\"text\":\"" << jsonEscape(output)
        << "\",\"tokensPerSecond\":" << tokensPerSecond
        << ",\"promptTokens\":" << promptTokens.size()
        << ",\"generatedTokens\":" << generated
        << ",\"promptTokensPerSecond\":" << promptTokensPerSecond
        << ",\"commandPromptChars\":" << promptChars
        << ",\"systemPromptChars\":" << systemChars
        << ",\"formattedPromptChars\":" << formattedChars
        << ",\"contextSize\":" << engine->context_size
        << ",\"batchSize\":" << engine->batch_size
        << ",\"promptMs\":" << (promptSeconds * 1000.0)
        << ",\"formatMs\":" << (std::chrono::duration_cast<std::chrono::duration<double, std::milli>>(tFormatted - tStart).count())
        << ",\"tokenizeMs\":" << (std::chrono::duration_cast<std::chrono::duration<double, std::milli>>(tTokenized - tFormatted).count())
        << ",\"decodePromptMs\":" << (promptSeconds * 1000.0)
        << ",\"generationMs\":" << (generationSeconds * 1000.0)
        << ",\"generationTokensPerSecond\":" << generationTokensPerSecond
        << ",\"effectiveThreads\":" << engine->threads
        << ",\"affinity\":\"" << jsonEscape(engine->affinity_info)
        << "\",\"affinityCurrent\":\"" << jsonEscape(affinityMaskDescription())
        << "\",\"systemInfo\":\"" << jsonEscape(engine->system_info)
        << "\",\"backend\":\"llama.cpp-" << jsonEscape(llama_version())
        << "\"}";

    return env->NewStringUTF(result.str().c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_yasn198020_aicontrol_MarfaLlamaNative_nativeRelease(
        JNIEnv *,
        jobject,
        jlong handle) {
    auto * engine = reinterpret_cast<NativeEngine *>(handle);
    if (engine == nullptr) {
        return;
    }

    if (engine->context) {
        llama_free(engine->context);
        engine->context = nullptr;
    }
    if (engine->model) {
        llama_model_free(engine->model);
        engine->model = nullptr;
    }
    delete engine;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_yasn198020_aicontrol_MarfaLlamaNative_nativeVersion(
        JNIEnv * env,
        jobject,
        jlong handle) {
    auto * engine = reinterpret_cast<NativeEngine *>(handle);
    if (engine == nullptr) {
        return env->NewStringUTF("unknown");
    }
    return env->NewStringUTF(llama_version());
}
