#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

#include "llama.h"

namespace {

constexpr const char * TAG = "MarfaLlamaNative";
constexpr int BATCH_SIZE = 512;

struct NativeEngine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    int threads = 0;
    int context_size = 0;
};

void logInfo(const std::string & message) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "%s", message.c_str());
}

void logError(const std::string & message) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", message.c_str());
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
    contextParams.n_threads = std::max(1, static_cast<int>(threads));
    contextParams.n_threads_batch = std::max(1, static_cast<int>(threads));
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

    const std::string info =
            "Native llama.cpp loaded: version=" + std::string(llama_version()) +
            ", threads=" + std::to_string(engine->threads) +
            ", context=" + std::to_string(engine->context_size);
    logInfo(info);

    return reinterpret_cast<jlong>(engine);
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

    std::string formatted;
    if (!formatChat(engine->model, systemPrompt, userPrompt, formatted)) {
        return env->NewStringUTF("{\"error\":\"chat template failed\"}");
    }

    const llama_vocab * vocab = llama_model_get_vocab(engine->model);
    std::vector<llama_token> promptTokens;
    if (!tokenizePrompt(vocab, formatted, promptTokens)) {
        return env->NewStringUTF("{\"error\":\"tokenization failed\"}");
    }

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

    llama_sampler_free(sampler);

    std::ostringstream result;
    result.setf(std::ios::fixed);
    result.precision(4);
    result
        << "{\"text\":\"" << jsonEscape(output)
        << "\",\"tokensPerSecond\":" << tokensPerSecond
        << ",\"promptTokens\":" << promptTokens.size()
        << ",\"generatedTokens\":" << generated
        << ",\"backend\":\"llama.cpp-" << jsonEscape(llama_version())
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
