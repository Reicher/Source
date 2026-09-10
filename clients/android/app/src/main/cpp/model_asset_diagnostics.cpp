#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <jni.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <iomanip>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include "llama.h"
#include "source_ai_backend.h"

namespace {

struct VirtualModel {
    std::vector<AAsset *> assets;
    std::vector<int64_t> starts;
    int64_t length = 0;
    int64_t position = 0;

    ~VirtualModel() {
        for (AAsset * asset : assets) AAsset_close(asset);
    }
};

int read_virtual_model(void * raw, char * buffer, int requested) {
    auto * model = static_cast<VirtualModel *>(raw);
    int total = 0;
    while (requested > 0 && model->position < model->length) {
        size_t index = model->assets.size() - 1;
        for (size_t candidate = 0; candidate + 1 < model->starts.size(); ++candidate) {
            if (model->position < model->starts[candidate + 1]) {
                index = candidate;
                break;
            }
        }
        const int64_t local = model->position - model->starts[index];
        const int64_t available = AAsset_getLength64(model->assets[index]) - local;
        const int amount = static_cast<int>(std::min<int64_t>(requested, available));
        if (AAsset_seek64(model->assets[index], local, SEEK_SET) < 0) return total > 0 ? total : -1;
        const int read = AAsset_read(model->assets[index], buffer + total, amount);
        if (read <= 0) return total > 0 ? total : read;
        model->position += read;
        requested -= read;
        total += read;
    }
    return total;
}

fpos64_t seek_virtual_model(void * raw, fpos64_t offset, int whence) {
    auto * model = static_cast<VirtualModel *>(raw);
    int64_t next;
    switch (whence) {
        case SEEK_SET: next = offset; break;
        case SEEK_CUR: next = model->position + offset; break;
        case SEEK_END: next = model->length + offset; break;
        default: return -1;
    }
    if (next < 0 || next > model->length) return -1;
    model->position = next;
    return next;
}

int close_virtual_model(void * raw) {
    delete static_cast<VirtualModel *>(raw);
    return 0;
}

void throw_java(JNIEnv * env, const char * message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(type, message);
}

std::unique_ptr<VirtualModel> open_virtual_model(
    JNIEnv * env,
    AAssetManager * manager,
    jobjectArray part_names
) {
    auto virtual_model = std::make_unique<VirtualModel>();
    const jsize count = env->GetArrayLength(part_names);
    if (count < 1) throw std::runtime_error("No model parts were provided");
    for (jsize index = 0; index < count; ++index) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(part_names, index));
        const char * name = env->GetStringUTFChars(value, nullptr);
        AAsset * asset = AAssetManager_open(manager, name, AASSET_MODE_RANDOM);
        env->ReleaseStringUTFChars(value, name);
        env->DeleteLocalRef(value);
        if (!asset) throw std::runtime_error("An install-time model part is missing");
        virtual_model->starts.push_back(virtual_model->length);
        virtual_model->length += AAsset_getLength64(asset);
        virtual_model->assets.push_back(asset);
    }
    return virtual_model;
}

FILE * open_virtual_file(std::unique_ptr<VirtualModel> virtual_model) {
    FILE * file = funopen64(
        virtual_model.release(),
        read_virtual_model,
        nullptr,
        seek_virtual_model,
        close_virtual_model
    );
    if (!file) throw std::runtime_error("Could not create a virtual GGUF stream");
    return file;
}

double milliseconds_since(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - start
    ).count();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_source_client_ai_ModelAssetDiagnostics_probeNative(
    JNIEnv * env,
    jobject,
    jobject asset_manager,
    jobjectArray part_names,
    jboolean load_tensors
) {
    try {
        AAssetManager * manager = AAssetManager_fromJava(env, asset_manager);
        if (!manager) throw std::runtime_error("AssetManager is unavailable");

        auto virtual_model = open_virtual_model(env, manager, part_names);

        const int64_t logical_bytes = virtual_model->length;
        const jsize count = env->GetArrayLength(part_names);
        FILE * file = open_virtual_file(std::move(virtual_model));

        source_ai_backend_init();
        llama_model_params params = llama_model_default_params();
        params.vocab_only = load_tensors != JNI_TRUE;
        params.load_mode = LLAMA_LOAD_MODE_NONE;
        llama_model * model = llama_model_load_from_file_ptr(file, params);
        if (!model) {
            fclose(file);
            throw std::runtime_error("llama.cpp could not load the virtual GGUF stream");
        }

        char description[256] = {};
        llama_model_desc(model, description, sizeof(description));
        const char * chat_template = llama_model_chat_template(model, nullptr);
        std::ostringstream result;
        result << "model=" << description
               << ";logicalBytes=" << logical_bytes
               << ";parts=" << count
               << ";tensorsLoaded=" << (load_tensors == JNI_TRUE ? "true" : "false")
               << ";officialChatTemplate=" << (chat_template && std::strlen(chat_template) > 0 ? "true" : "false");

        llama_model_free(model);
        fclose(file);
        return env->NewStringUTF(result.str().c_str());
    } catch (const std::exception & error) {
        throw_java(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_source_client_ai_ModelAssetDiagnostics_benchmarkNative(
    JNIEnv * env,
    jobject,
    jobject asset_manager,
    jobjectArray part_names,
    jstring prompt_value,
    jint context_tokens,
    jint maximum_output_tokens,
    jint threads
) {
    FILE * file = nullptr;
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    llama_sampler * sampler = nullptr;
    try {
        if (context_tokens < 256 || maximum_output_tokens < 1 || threads < 1) {
            throw std::runtime_error("Invalid benchmark parameters");
        }
        AAssetManager * manager = AAssetManager_fromJava(env, asset_manager);
        if (!manager) throw std::runtime_error("AssetManager is unavailable");
        const char * prompt_chars = env->GetStringUTFChars(prompt_value, nullptr);
        std::string prompt(prompt_chars);
        env->ReleaseStringUTFChars(prompt_value, prompt_chars);
        if (prompt.empty()) throw std::runtime_error("The benchmark prompt is empty");

        file = open_virtual_file(open_virtual_model(env, manager, part_names));
        source_ai_backend_init();

        const auto load_started = std::chrono::steady_clock::now();
        llama_model_params model_params = llama_model_default_params();
        model_params.load_mode = LLAMA_LOAD_MODE_NONE;
        model = llama_model_load_from_file_ptr(file, model_params);
        if (!model) throw std::runtime_error("llama.cpp could not load the benchmark model");
        const double load_ms = milliseconds_since(load_started);
        fclose(file);
        file = nullptr;

        const char * chat_template = llama_model_chat_template(model, nullptr);
        if (!chat_template || std::strlen(chat_template) == 0) {
            throw std::runtime_error("The model has no official chat template");
        }
        llama_chat_message message{"user", prompt.c_str()};
        std::vector<char> formatted(prompt.size() * 2 + 1024);
        int32_t formatted_size = llama_chat_apply_template(
            chat_template, &message, 1, true, formatted.data(), formatted.size()
        );
        if (formatted_size > static_cast<int32_t>(formatted.size())) {
            formatted.resize(formatted_size);
            formatted_size = llama_chat_apply_template(
                chat_template, &message, 1, true, formatted.data(), formatted.size()
            );
        }
        if (formatted_size < 0) throw std::runtime_error("Could not apply the official chat template");

        const llama_vocab * vocab = llama_model_get_vocab(model);
        int32_t prompt_token_count = -llama_tokenize(
            vocab, formatted.data(), formatted_size, nullptr, 0, true, true
        );
        if (prompt_token_count < 1) throw std::runtime_error("Could not size the benchmark tokens");
        if (prompt_token_count + maximum_output_tokens > context_tokens) {
            throw std::runtime_error("Benchmark prompt and output exceed the configured context");
        }
        std::vector<llama_token> prompt_tokens(prompt_token_count);
        if (llama_tokenize(
            vocab,
            formatted.data(),
            formatted_size,
            prompt_tokens.data(),
            prompt_tokens.size(),
            true,
            true
        ) < 0) {
            throw std::runtime_error("Could not tokenize the benchmark prompt");
        }

        llama_context_params context_params = llama_context_default_params();
        context_params.n_ctx = context_tokens;
        context_params.n_batch = std::min<int32_t>(context_tokens, 512);
        context_params.n_ubatch = std::min<int32_t>(context_tokens, 512);
        context_params.n_threads = threads;
        context_params.n_threads_batch = threads;
        context = llama_init_from_model(model, context_params);
        if (!context) throw std::runtime_error("Could not create the benchmark context");

        sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.6f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

        llama_batch batch = llama_batch_get_one(prompt_tokens.data(), prompt_tokens.size());
        const auto prompt_started = std::chrono::steady_clock::now();
        if (llama_decode(context, batch) != 0) throw std::runtime_error("Prompt decoding failed");
        const double prompt_ms = milliseconds_since(prompt_started);

        int32_t output_tokens = 0;
        int64_t output_bytes = 0;
        bool stopped = false;
        const auto generation_started = std::chrono::steady_clock::now();
        while (output_tokens < maximum_output_tokens) {
            llama_token token = llama_sampler_sample(sampler, context, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                stopped = true;
                break;
            }

            char piece[256];
            int32_t piece_size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, true);
            if (piece_size < 0) output_bytes += -piece_size;
            else output_bytes += piece_size;
            output_tokens += 1;
            if (output_tokens == maximum_output_tokens) break;
            batch = llama_batch_get_one(&token, 1);
            if (llama_decode(context, batch) != 0) throw std::runtime_error("Token decoding failed");
        }
        const double generation_ms = milliseconds_since(generation_started);
        const double tokens_per_second = generation_ms > 0
            ? output_tokens * 1000.0 / generation_ms
            : 0.0;

        std::ostringstream result;
        result << std::fixed << std::setprecision(2)
               << "loadMs=" << load_ms
               << ";promptTokens=" << prompt_token_count
               << ";promptMs=" << prompt_ms
               << ";outputTokens=" << output_tokens
               << ";generationMs=" << generation_ms
               << ";tokensPerSecond=" << tokens_per_second
               << ";outputBytes=" << output_bytes
               << ";finishReason=" << (stopped ? "stop" : "length")
               << ";seed=42"
               << ";threads=" << threads
               << ";contextTokens=" << context_tokens;

        llama_sampler_free(sampler);
        llama_free(context);
        llama_model_free(model);
        return env->NewStringUTF(result.str().c_str());
    } catch (const std::exception & error) {
        if (sampler) llama_sampler_free(sampler);
        if (context) llama_free(context);
        if (model) llama_model_free(model);
        if (file) fclose(file);
        throw_java(env, error.what());
        return nullptr;
    }
}
