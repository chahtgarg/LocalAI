
#include <jni.h>
#include <string>
#include <vector>
#include <chrono>
#include <sstream>
#include <iomanip>
#include <fstream>
#include <cstring>
#include "llama.h"

// Stores the loaded language model.
static struct llama_model* model = nullptr;

// Tracks whether the llama.cpp backend has been initialized.
static bool backendInitialized = false;


// -------- SETTINGS --------

// Maximum number of new tokens the model can generate.
static const int MAX_NEW_TOKENS = 256;

// Number of CPU threads used for inference.
static const int N_THREADS = 4;

// Maximum context length.
static const int N_CTX = 1024;

// Batch size used while processing tokens.
static const int N_BATCH = 512;

// --------------------------


// Reads memory information from the Android process.
static double readStatusMB(const char* key) {

    std::ifstream file("/proc/self/status");
    std::string line;
    size_t keyLen = std::strlen(key);

    // Searches the process status file for a requested memory value.
    while (std::getline(file, line)) {

        if (line.compare(0, keyLen, key) == 0) {
            try {
                // Converts memory from KB to MB.
                return std::stol(line.substr(keyLen)) / 1024.0;
            } catch (...) {
                return -1.0;
            }
        }
    }

    return -1.0;
}


// Adds tokens to an inference batch and assigns their positions.
static void setBatchTokens(
        llama_batch_ext* batch,
        const llama_token* tokens,
        int count,
        llama_pos startPosition) {

    llama_batch_ext_clear(batch);

    for (int i = 0; i < count; i++) {

        // Adds a token to the batch.
        int index = llama_batch_ext_add_token(batch, 0, tokens[i]);

        // Assigns its position in the sequence.
        llama_pos position = startPosition + i;
        llama_batch_ext_set_pos(batch, index, &position);
    }

    // Requests output logits for the final token in this batch.
    llama_batch_ext_set_output_logits(batch, count - 1, true);
}


// Simple JNI test function to check native connectivity.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_localai_MainActivity_stringFromJNI(
        JNIEnv* env, jobject) {

    return env->NewStringUTF("Native C++ connected successfully!");
}


// ==================================================
// STEP 1: LOAD THE MODEL
// ==================================================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_localai_MainActivity_loadModel(
        JNIEnv* env, jobject, jstring modelPath) {

    // Converts the Java/Kotlin string into a C++ string.
    const char* path = env->GetStringUTFChars(modelPath, nullptr);

    if (!path) {
        return env->NewStringUTF("Could not read model path.");
    }

    // Initializes llama.cpp backend only once.
    if (!backendInitialized) {
        llama_backend_init();
        backendInitialized = true;
    }

    // Frees an older model if the user selects another model.
    if (model != nullptr) {
        llama_model_free(model);
        model = nullptr;
    }

    // Gets the default model-loading parameters.
    llama_model_params params = llama_model_default_params();

    // Disables GPU offloading: inference runs on CPU.
    params.n_gpu_layers = 0;

    // Measures model loading time.
    auto loadStart = std::chrono::steady_clock::now();

    // Loads the GGUF model using llama.cpp.
    model = llama_model_load_from_file(path, params);

    double loadMs = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - loadStart).count();

    // Releases the converted string after using it.
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        return env->NewStringUTF("Model loading failed.");
    }

    // Creates a message containing the loading result and time.
    std::ostringstream out;
    out << std::fixed << std::setprecision(0);
    out << "Model loaded successfully! (load time: " << loadMs << " ms)";

    return env->NewStringUTF(out.str().c_str());
}


// ==================================================
// STEP 2: GENERATE A RESPONSE
// ==================================================

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_localai_MainActivity_generateResponse(
        JNIEnv* env, jobject, jstring userInput) {

    // Checks whether a model has been loaded.
    if (!model) {
        return env->NewStringUTF("Please load a model first.");
    }

    // Converts the user's input from Kotlin into C++.
    const char* input = env->GetStringUTFChars(userInput, nullptr);

    if (!input) {
        return env->NewStringUTF("Could not read your prompt.");
    }

    std::string userText(input);

    // Releases the original JNI string.
    env->ReleaseStringUTFChars(userInput, input);


    // ==================================================
    // STEP 3: PREPARE AND TOKENISE THE PROMPT
    // ==================================================

    // Gives the model a basic system instruction.
    std::string systemText = "You are a helpful assistant.";

    std::string prompt;
    bool usedTemplate = false;

    // Gets the chat template associated with the selected model.
    const char* tmpl = llama_model_chat_template(model, nullptr);

    if (tmpl != nullptr) {

        // Creates system and user messages.
        llama_chat_message msgs[2];

        msgs[0].role = "system";
        msgs[0].content = systemText.c_str();

        msgs[1].role = "user";
        msgs[1].content = userText.c_str();

        // Buffer stores the formatted chat prompt.
        std::vector<char> buf(8192);

        // Applies the model's own chat template.
        int n = llama_chat_apply_template(
                tmpl, msgs, 2, true, buf.data(), (int) buf.size());

        // Expands the buffer if the formatted prompt is too large.
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

    // Uses a basic fallback format if no chat template is available.
    if (!usedTemplate) {
        prompt = systemText + "\nUser: " + userText + "\nAssistant:";
    }

    // Gets the model vocabulary used for tokenisation.
    const llama_vocab* vocab = llama_model_get_vocab(model);

    // Avoids adding another special token if the chat template already added it.
    const bool addSpecial = !usedTemplate;

    // First determines how many tokens are required.
    int tokenCount = -llama_tokenize(
            vocab, prompt.c_str(), prompt.size(),
            nullptr, 0, addSpecial, true);

    // Checks whether the prompt fits within the context limit.
    if (tokenCount <= 0 || tokenCount > N_CTX - MAX_NEW_TOKENS) {
        return env->NewStringUTF("Prompt is too long or could not be tokenized.");
    }

    // Creates storage for the numerical token IDs.
    std::vector<llama_token> tokens(tokenCount);

    // Converts the prompt text into token IDs.
    if (llama_tokenize(
            vocab, prompt.c_str(), prompt.size(),
            tokens.data(), tokens.size(), addSpecial, true) < 0) {

        return env->NewStringUTF("Prompt tokenization failed.");
    }


    // ==================================================
    // STEP 4: CREATE THE INFERENCE CONTEXT
    // ==================================================

    // Gets the default context configuration.
    llama_context_params contextParams = llama_context_default_params();

    // Sets context length, batch size and CPU threads.
    contextParams.n_ctx           = N_CTX;
    contextParams.n_batch         = N_BATCH;
    contextParams.n_threads       = N_THREADS;
    contextParams.n_threads_batch = N_THREADS;

    // Creates an inference context using the loaded model.
    llama_context* ctx = llama_init_from_model(model, contextParams);

    if (!ctx) {
        return env->NewStringUTF("Could not create inference context.");
    }


    // ==================================================
    // STEP 5: SET UP THE SAMPLER AND GENERATE TOKENS
    // ==================================================

    // Creates a sampler chain to choose the next token.
    llama_sampler* sampler = llama_sampler_chain_init(
            llama_sampler_chain_default_params());

    // Greedy sampling selects the token with the highest score.
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    // Creates a batch for processing input and generated tokens.
    llama_batch_ext* batch = llama_batch_ext_init(ctx);

    if (!batch) {
        llama_sampler_free(sampler);
        llama_free(ctx);
        return env->NewStringUTF("Could not create inference batch.");
    }


    // ==================================================
    // PERFORMANCE MEASUREMENTS
    // ==================================================

    using Clock = std::chrono::steady_clock;

    auto totalStart = Clock::now();

    double prefillMs = 0.0;
    double decodeMs = 0.0;
    double ttftMs = 0.0;

    int generatedTokens = 0;
    int position = 0;
    int batchTokens = tokenCount;

    std::string response;

    // Adds all prompt tokens to the initial batch.
    setBatchTokens(batch, tokens.data(), tokenCount, 0);


    // Generates one token at a time, up to the configured limit.
    for (int i = 0; i < MAX_NEW_TOKENS; i++) {

        auto stepStart = Clock::now();

        // Processes the current batch using llama.cpp.
        if (llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
            response = "Model decoding failed.";
            break;
        }

        double processMs = std::chrono::duration<double, std::milli>(
                Clock::now() - stepStart).count();

        // First processing step is measured as prefill.
        if (i == 0) {
            prefillMs = processMs;
        } else {
            // Later processing steps contribute to decode time.
            decodeMs += processMs;
        }

        position += batchTokens;

        // Selects the next token using the sampler.
        llama_token nextToken = llama_sampler_sample(sampler, ctx, -1);

        // Stops generation if the model produces an end-of-generation token.
        if (llama_vocab_is_eog(vocab, nextToken)) {
            break;
        }

        char buffer[256];

        // Converts the generated token ID into readable text.
        int pieceLength = llama_token_to_piece(
                vocab, nextToken, buffer, sizeof(buffer), 0, true);

        if (pieceLength < 0) {
            response = "Could not convert generated token.";
            break;
        }

        // Adds the generated text to the final response.
        response.append(buffer, pieceLength);

        generatedTokens++;

        // Measures time until the first generated token is available.
        if (generatedTokens == 1) {
            ttftMs = std::chrono::duration<double, std::milli>(
                    Clock::now() - totalStart).count();
        }

        // Prepares the generated token for the next inference step.
        setBatchTokens(batch, &nextToken, 1, position);

        // After the initial prompt, each batch contains one new token.
        batchTokens = 1;
    }


    // ==================================================
    // PERFORMANCE CALCULATIONS
    // ==================================================

    double totalMs = std::chrono::duration<double, std::milli>(
            Clock::now() - totalStart).count();

    // Calculates prompt processing speed.
    double prefillTPS =
            prefillMs > 0 ? tokenCount / (prefillMs / 1000.0) : 0;

    // Calculates token generation speed.
    double decodeTPS =
            (decodeMs > 0 && generatedTokens > 1)
            ? (generatedTokens - 1) / (decodeMs / 1000.0)
            : 0;

    // Calculates overall generation throughput.
    double overallTPS =
            totalMs > 0 ? generatedTokens / (totalMs / 1000.0) : 0;


    // Reads memory measurements before freeing the context.
    double peakRamMB = readStatusMB("VmHWM:");
    double currentRamMB = readStatusMB("VmRSS:");


    // Frees temporary inference resources.
    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    llama_free(ctx);


    // ==================================================
    // RETURN RESPONSE AND PERFORMANCE METRICS
    // ==================================================

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

    // Appends performance information to the generated answer.
    response += metrics.str();

    // Converts the final C++ string into a Kotlin-readable string.
    return env->NewStringUTF(response.c_str());
}
