
#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <sstream>
#include <iomanip>
#include <fstream>
#include <cstring>
#include "llama.h"

static struct llama_model* model = nullptr;
static bool backendInitialized = false;

// ---- Tunable settings (change only here) ----
static const int MAX_NEW_TOKENS = 256;
static const int N_THREADS      = 4;
static const int N_CTX          = 1024;
static const int N_BATCH        = 512;
// ---------------------------------------------

// Read memory information from the current Android process.
static double readStatusMB(const char* key) {
    std::ifstream file("/proc/self/status");
    std::string line;
    size_t keyLen = std::strlen(key);

    while (std::getline(file, line)) {
        if (line.compare(0, keyLen, key) == 0) {
            try {
                return std::stol(line.substr(keyLen)) / 1024.0;
            } catch (...) {
                return -1.0;
            }
        }
    }

    return -1.0;
}

static void setBatchTokens(
        llama_batch_ext* batch,
        const llama_token* tokens,
        int count,
        llama_pos startPosition) {

    llama_batch_ext_clear(batch);

    for (int i = 0; i < count; i++) {
        int index = llama_batch_ext_add_token(batch, 0, tokens[i]);
        llama_pos position = startPosition + i;
        llama_batch_ext_set_pos(batch, index, &position);
    }

    llama_batch_ext_set_output_logits(batch, count - 1, true);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_localai_MainActivity_stringFromJNI(
        JNIEnv* env, jobject) {
    return env->NewStringUTF("Native C++ connected successfully!");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_localai_MainActivity_loadModel(
        JNIEnv* env, jobject, jstring modelPath) {

    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    if (!path) {
        return env->NewStringUTF("Could not read model path.");
    }

    if (!backendInitialized) {
        llama_backend_init();
        backendInitialized = true;
    }

    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }

    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;

    auto loadStart = std::chrono::steady_clock::now();
    model = llama_model_load_from_file(path, params);

    double loadMs = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - loadStart).count();

    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        return env->NewStringUTF("Model loading failed.");
    }

    std::ostringstream out;
    out << std::fixed << std::setprecision(0);
    out << "Model loaded successfully! (load time: " << loadMs << " ms)";

    return env->NewStringUTF(out.str().c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_localai_MainActivity_generateResponse(
        JNIEnv* env, jobject, jstring userInput) {

    if (!model) {
        return env->NewStringUTF("Please load a model first.");
    }

    const char* input = env->GetStringUTFChars(userInput, nullptr);

    if (!input) {
        return env->NewStringUTF("Could not read your prompt.");
    }

    std::string userText(input);
    env->ReleaseStringUTFChars(userInput, input);

    // ---- Build prompt using the model's own chat template ----
    std::string systemText = "You are a helpful assistant.";
    std::string prompt;
    bool usedTemplate = false;

    const char* tmpl = llama_model_chat_template(model, nullptr);

    if (tmpl != nullptr) {
        llama_chat_message msgs[2];

        msgs[0].role = "system";
        msgs[0].content = systemText.c_str();

        msgs[1].role = "user";
        msgs[1].content = userText.c_str();

        std::vector<char> buf(8192);

        int n = llama_chat_apply_template(
                tmpl, msgs, 2, true, buf.data(), (int) buf.size());

        if (n > (int) buf.size()) {
            buf.resize(n);
            n = llama_chat_apply_template(
                    tmpl, msgs, 2, true, buf.data(), (int) buf.size());
        }

        if (n > 0) {
            prompt.assign(buf.data(), n);
            usedTemplate = true;
        }
    }

    if (!usedTemplate) {
        prompt = systemText + "\nUser: " + userText + "\nAssistant:";
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    // The template already contains BOS where needed.
    const bool addSpecial = !usedTemplate;

    int tokenCount = -llama_tokenize(
            vocab, prompt.c_str(), prompt.size(),
            nullptr, 0, addSpecial, true);

    if (tokenCount <= 0 || tokenCount > N_CTX - MAX_NEW_TOKENS) {
        return env->NewStringUTF("Prompt is too long or could not be tokenized.");
    }

    std::vector<llama_token> tokens(tokenCount);

    if (llama_tokenize(
            vocab, prompt.c_str(), prompt.size(),
            tokens.data(), tokens.size(), addSpecial, true) < 0) {
        return env->NewStringUTF("Prompt tokenization failed.");
    }

    // ---- Context ----
    llama_context_params contextParams = llama_context_default_params();

    contextParams.n_ctx           = N_CTX;
    contextParams.n_batch         = N_BATCH;
    contextParams.n_threads       = N_THREADS;
    contextParams.n_threads_batch = N_THREADS;

    llama_context* ctx = llama_init_from_model(model, contextParams);

    if (!ctx) {
        return env->NewStringUTF("Could not create inference context.");
    }

    llama_sampler* sampler = llama_sampler_chain_init(
            llama_sampler_chain_default_params());

    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    llama_batch_ext* batch = llama_batch_ext_init(ctx);

    if (!batch) {
        llama_sampler_free(sampler);
        llama_free(ctx);
        return env->NewStringUTF("Could not create inference batch.");
    }

    using Clock = std::chrono::steady_clock;

    auto totalStart = Clock::now();

    double prefillMs = 0.0;
    double decodeMs = 0.0;
    double ttftMs = 0.0;

    int generatedTokens = 0;
    int position = 0;
    int batchTokens = tokenCount;

    std::string response;

    setBatchTokens(batch, tokens.data(), tokenCount, 0);

    for (int i = 0; i < MAX_NEW_TOKENS; i++) {

        auto stepStart = Clock::now();

        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            response = "Model decoding failed.";
            break;
        }

        double processMs = std::chrono::duration<double, std::milli>(
                Clock::now() - stepStart).count();

        if (i == 0) {
            prefillMs = processMs;
        } else {
            decodeMs += processMs;
        }

        position += batchTokens;

        llama_token nextToken = llama_sampler_sample(sampler, ctx, -1);

        if (llama_vocab_is_eog(vocab, nextToken)) {
            break;
        }

        char buffer[256];

        int pieceLength = llama_token_to_piece(
                vocab, nextToken, buffer, sizeof(buffer), 0, true);

        if (pieceLength < 0) {
            response = "Could not convert generated token.";
            break;
        }

        response.append(buffer, pieceLength);
        generatedTokens++;

        if (generatedTokens == 1) {
            ttftMs = std::chrono::duration<double, std::milli>(
                    Clock::now() - totalStart).count();
        }

        setBatchTokens(batch, &nextToken, 1, position);
        batchTokens = 1;
    }

    double totalMs = std::chrono::duration<double, std::milli>(
            Clock::now() - totalStart).count();

    double prefillTPS =
            prefillMs > 0 ? tokenCount / (prefillMs / 1000.0) : 0;

    double decodeTPS =
            (decodeMs > 0 && generatedTokens > 1)
            ? (generatedTokens - 1) / (decodeMs / 1000.0)
            : 0;

    double overallTPS =
            totalMs > 0 ? generatedTokens / (totalMs / 1000.0) : 0;

    // Capture RAM before freeing the inference context.
    double peakRamMB = readStatusMB("VmHWM:");
    double currentRamMB = readStatusMB("VmRSS:");

    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    llama_free(ctx);

    std::ostringstream metrics;
    metrics << std::fixed << std::setprecision(2);

    metrics << "\n\n--- PERFORMANCE ---\n";
    metrics << "Chat template used: " << (usedTemplate ? "yes" : "no") << "\n";
    metrics << "Threads: " << N_THREADS << "\n";
    metrics << "Prompt tokens: " << tokenCount << "\n";
    metrics << "Generated tokens: " << generatedTokens << "\n";
    metrics << "Prefill time: " << prefillMs << " ms\n";
    metrics << "Time to first token: " << ttftMs << " ms\n";
    metrics << "Decode processing time: " << decodeMs << " ms\n";
    metrics << "Total generation time: " << totalMs << " ms\n";
    metrics << "Prefill throughput: " << prefillTPS << " tokens/sec\n";
    metrics << "Decode throughput: " << decodeTPS << " tokens/sec\n";
    metrics << "Overall throughput: " << overallTPS << " tokens/sec\n";
    metrics << "Peak RAM (VmHWM): " << peakRamMB << " MB\n";
    metrics << "Current RAM (VmRSS): " << currentRamMB << " MB";

    response += metrics.str();

    return env->NewStringUTF(response.c_str());
}
