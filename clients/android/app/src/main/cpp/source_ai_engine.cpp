#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

#include "llama.h"
#include "chat.h"
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

void throw_java(JNIEnv * env, const char * message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(type, message);
}

std::string from_java_bytes(JNIEnv * env, jbyteArray value) {
    const jsize size = env->GetArrayLength(value);
    std::string result(static_cast<size_t>(size), '\0');
    if (size > 0) {
        env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(result.data()));
    }
    return result;
}

jstring to_java_utf8(JNIEnv * env, const std::string & value) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (!bytes) return nullptr;
    if (!value.empty()) {
        env->SetByteArrayRegion(
            bytes,
            0,
            static_cast<jsize>(value.size()),
            reinterpret_cast<const jbyte *>(value.data())
        );
    }
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/nio/charset/Charset;)V");
    jclass charset_class = env->FindClass("java/nio/charset/StandardCharsets");
    jfieldID utf8_field = env->GetStaticFieldID(
        charset_class,
        "UTF_8",
        "Ljava/nio/charset/Charset;"
    );
    jobject utf8 = env->GetStaticObjectField(charset_class, utf8_field);
    auto result = static_cast<jstring>(env->NewObject(string_class, constructor, bytes, utf8));
    env->DeleteLocalRef(utf8);
    env->DeleteLocalRef(charset_class);
    env->DeleteLocalRef(string_class);
    env->DeleteLocalRef(bytes);
    return result;
}

struct SourceAiEngine {
    llama_model * model = nullptr;
    common_chat_templates_ptr chat_templates;
    std::mutex generation_mutex;
    std::mutex state_mutex;
    std::atomic_bool cancel_requested{false};
    std::string active_run_id;

    ~SourceAiEngine() {
        chat_templates.reset();
        if (model) llama_model_free(model);
    }
};

struct ActiveRun {
    SourceAiEngine * engine;

    ActiveRun(SourceAiEngine * value, std::string run_id) : engine(value) {
        std::lock_guard<std::mutex> lock(engine->state_mutex);
        engine->cancel_requested.store(false);
        engine->active_run_id = std::move(run_id);
    }

    ~ActiveRun() {
        std::lock_guard<std::mutex> lock(engine->state_mutex);
        engine->active_run_id.clear();
        engine->cancel_requested.store(false);
    }
};

bool abort_generation(void * raw) {
    return static_cast<SourceAiEngine *>(raw)->cancel_requested.load();
}

class Cancelled final : public std::exception {
public:
    const char * what() const noexcept override { return "Generation was cancelled"; }
};

void decode(SourceAiEngine * engine, llama_context * context, llama_batch batch) {
    const int result = llama_decode(context, batch);
    if (result == 2 && engine->cancel_requested.load()) throw Cancelled();
    if (result != 0) throw std::runtime_error("llama.cpp decoding failed");
}

std::string apply_chat_template(
    SourceAiEngine * engine,
    const std::vector<std::string> & roles,
    const std::vector<std::string> & contents,
    size_t start
) {
    common_chat_templates_inputs inputs;
    inputs.add_generation_prompt = true;
    inputs.use_jinja = true;
    inputs.enable_thinking = false;
    inputs.reasoning_format = COMMON_REASONING_FORMAT_NONE;
    for (size_t index = start; index < roles.size(); ++index) {
        common_chat_msg message;
        message.role = roles[index];
        message.content = contents[index];
        inputs.messages.push_back(std::move(message));
    }
    return common_chat_templates_apply(engine->chat_templates.get(), inputs).prompt;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & prompt) {
    const int32_t count = -llama_tokenize(
        vocab,
        prompt.data(),
        static_cast<int32_t>(prompt.size()),
        nullptr,
        0,
        true,
        true
    );
    if (count < 1) throw std::runtime_error("Could not size the chat prompt tokens");
    std::vector<llama_token> tokens(static_cast<size_t>(count));
    const int32_t written = llama_tokenize(
        vocab,
        prompt.data(),
        static_cast<int32_t>(prompt.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        true
    );
    if (written != count) throw std::runtime_error("Could not tokenize the chat prompt");
    return tokens;
}

std::vector<llama_token> token_bounded_prompt(
    SourceAiEngine * engine,
    const std::vector<std::string> & roles,
    const std::vector<std::string> & contents,
    int32_t context_tokens,
    int32_t maximum_output_tokens,
    int32_t & dropped_messages
) {
    const llama_vocab * vocab = llama_model_get_vocab(engine->model);
    size_t start = 0;
    while (start < roles.size() && roles[start] == "assistant") ++start;
    while (start < roles.size()) {
        std::vector<llama_token> tokens = tokenize(
            vocab,
            apply_chat_template(engine, roles, contents, start)
        );
        if (static_cast<int32_t>(tokens.size()) + maximum_output_tokens <= context_tokens) {
            dropped_messages = static_cast<int32_t>(start);
            return tokens;
        }
        if (start + 1 >= roles.size()) break;
        ++start;
        while (start < roles.size() && roles[start] == "assistant") ++start;
    }
    throw std::runtime_error("The latest message exceeds the local token budget");
}

std::string token_piece(const llama_vocab * vocab, llama_token token) {
    char local[256];
    int32_t size = llama_token_to_piece(vocab, token, local, sizeof(local), 0, true);
    if (size >= 0) return {local, static_cast<size_t>(size)};
    std::vector<char> expanded(static_cast<size_t>(-size));
    size = llama_token_to_piece(
        vocab,
        token,
        expanded.data(),
        static_cast<int32_t>(expanded.size()),
        0,
        true
    );
    if (size < 0) throw std::runtime_error("Could not decode an output token");
    return {expanded.data(), static_cast<size_t>(size)};
}

size_t complete_utf8_prefix(const std::string & value) {
    size_t index = 0;
    while (index < value.size()) {
        const unsigned char first = static_cast<unsigned char>(value[index]);
        size_t width = 1;
        if ((first & 0x80) == 0) width = 1;
        else if ((first & 0xE0) == 0xC0) width = 2;
        else if ((first & 0xF0) == 0xE0) width = 3;
        else if ((first & 0xF8) == 0xF0) width = 4;
        if (index + width > value.size()) break;
        index += width;
    }
    return index;
}

class VisibleOutput {
public:
    std::vector<std::string> push(const std::string & piece) {
        visible_buffer_ += piece;
        if (!visible_started_) {
            const auto first = std::find_if_not(
                visible_buffer_.begin(),
                visible_buffer_.end(),
                [](unsigned char value) { return std::isspace(value); }
            );
            visible_buffer_.erase(visible_buffer_.begin(), first);
            if (visible_buffer_.empty()) return {};
            visible_started_ = true;
        }
        const size_t ready = complete_utf8_prefix(visible_buffer_);
        if (ready == 0) return {};
        std::string output = visible_buffer_.substr(0, ready);
        visible_buffer_.erase(0, ready);
        visible_bytes_ += output.size();
        return {std::move(output)};
    }

    bool has_visible_output() const { return visible_bytes_ > 0 || !visible_buffer_.empty(); }
private:
    bool visible_started_ = false;
    size_t visible_bytes_ = 0;
    std::string visible_buffer_;
};

void emit_token(JNIEnv * env, jobject listener, jmethodID callback, const std::string & text) {
    jstring value = to_java_utf8(env, text);
    if (!value) throw std::runtime_error("Could not allocate a streamed token");
    env->CallVoidMethod(listener, callback, value);
    env->DeleteLocalRef(value);
    if (env->ExceptionCheck()) throw std::runtime_error("The token listener failed");
}

std::string finish_result(
    const char * reason,
    int32_t input_tokens,
    int32_t output_tokens,
    int32_t dropped_messages
) {
    return std::string("finishReason=") + reason
        + ";inputTokens=" + std::to_string(input_tokens)
        + ";outputTokens=" + std::to_string(output_tokens)
        + ";droppedMessages=" + std::to_string(dropped_messages);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_source_client_ai_LlamaCppNative_createNative(
    JNIEnv * env,
    jobject,
    jobject asset_manager,
    jobjectArray part_names
) {
    FILE * file = nullptr;
    try {
        AAssetManager * manager = AAssetManager_fromJava(env, asset_manager);
        if (!manager) throw std::runtime_error("AssetManager is unavailable");
        source_ai_backend_init();
        file = open_virtual_file(open_virtual_model(env, manager, part_names));
        llama_model_params params = llama_model_default_params();
        params.load_mode = LLAMA_LOAD_MODE_NONE;
        auto engine = std::make_unique<SourceAiEngine>();
        engine->model = llama_model_load_from_file_ptr(file, params);
        fclose(file);
        file = nullptr;
        if (!engine->model) throw std::runtime_error("llama.cpp could not load the client model");
        engine->chat_templates = common_chat_templates_init(engine->model, "");
        if (!common_chat_templates_support_enable_thinking(engine->chat_templates.get())) {
            throw std::runtime_error("The official chat template cannot disable thinking");
        }
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception & error) {
        if (file) fclose(file);
        throw_java(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_source_client_ai_LlamaCppNative_generateNative(
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring run_id_value,
    jintArray role_values,
    jobjectArray content_values,
    jint context_tokens,
    jint maximum_output_tokens,
    jint threads,
    jobject listener
) {
    llama_context * context = nullptr;
    llama_sampler * sampler = nullptr;
    try {
        auto * engine = reinterpret_cast<SourceAiEngine *>(handle);
        if (!engine || !engine->model) throw std::runtime_error("The local AI engine is closed");
        if (context_tokens < 256 || maximum_output_tokens < 1 || threads < 1 ||
            maximum_output_tokens >= context_tokens) {
            throw std::runtime_error("Invalid local generation parameters");
        }
        const jsize message_count = env->GetArrayLength(role_values);
        if (message_count < 1 || message_count != env->GetArrayLength(content_values) || message_count > 64) {
            throw std::runtime_error("Invalid Source AI message list");
        }
        std::vector<jint> raw_roles(static_cast<size_t>(message_count));
        env->GetIntArrayRegion(role_values, 0, message_count, raw_roles.data());
        std::vector<std::string> roles;
        std::vector<std::string> contents;
        for (jsize index = 0; index < message_count; ++index) {
            if (raw_roles[index] == 0) roles.emplace_back("user");
            else if (raw_roles[index] == 1) roles.emplace_back("assistant");
            else throw std::runtime_error("Only user and assistant roles are supported");
            auto value = static_cast<jbyteArray>(env->GetObjectArrayElement(content_values, index));
            contents.push_back(from_java_bytes(env, value));
            env->DeleteLocalRef(value);
            if (contents.back().empty()) throw std::runtime_error("AI messages cannot be empty");
        }
        if (roles.back() != "user") throw std::runtime_error("The final AI message must be from the user");

        const char * run_chars = env->GetStringUTFChars(run_id_value, nullptr);
        std::string run_id(run_chars);
        env->ReleaseStringUTFChars(run_id_value, run_chars);
        if (run_id.empty()) throw std::runtime_error("The AI run identifier is empty");

        jclass listener_class = env->GetObjectClass(listener);
        jmethodID callback = env->GetMethodID(listener_class, "onToken", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(listener_class);
        if (!callback) throw std::runtime_error("The native token listener is unavailable");

        std::unique_lock<std::mutex> generation_lock(engine->generation_mutex);
        ActiveRun active(engine, run_id);
        int32_t dropped_messages = 0;
        std::vector<llama_token> prompt_tokens = token_bounded_prompt(
            engine,
            roles,
            contents,
            context_tokens,
            maximum_output_tokens,
            dropped_messages
        );

        llama_context_params params = llama_context_default_params();
        params.n_ctx = static_cast<uint32_t>(context_tokens);
        params.n_batch = static_cast<uint32_t>(std::min(context_tokens, 512));
        params.n_ubatch = static_cast<uint32_t>(std::min(context_tokens, 512));
        params.n_threads = threads;
        params.n_threads_batch = threads;
        params.abort_callback = abort_generation;
        params.abort_callback_data = engine;
        context = llama_init_from_model(engine->model, params);
        if (!context) throw std::runtime_error("Could not create the local AI context");

        sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.6f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        size_t offset = 0;
        while (offset < prompt_tokens.size()) {
            if (engine->cancel_requested.load()) throw Cancelled();
            const int32_t amount = static_cast<int32_t>(std::min<size_t>(512, prompt_tokens.size() - offset));
            llama_batch batch = llama_batch_get_one(prompt_tokens.data() + offset, amount);
            decode(engine, context, batch);
            offset += static_cast<size_t>(amount);
        }

        const llama_vocab * vocab = llama_model_get_vocab(engine->model);
        VisibleOutput visible;
        int32_t output_tokens = 0;
        bool stopped = false;
        while (output_tokens < maximum_output_tokens) {
            if (engine->cancel_requested.load()) throw Cancelled();
            const llama_token token = llama_sampler_sample(sampler, context, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                stopped = true;
                break;
            }
            ++output_tokens;
            for (const std::string & text : visible.push(token_piece(vocab, token))) {
                emit_token(env, listener, callback, text);
            }
            if (output_tokens == maximum_output_tokens) break;
            llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
            decode(engine, context, batch);
        }

        if (!visible.has_visible_output()) {
            throw std::runtime_error(
                stopped
                    ? "The local model stopped without a visible answer"
                    : "The local model exhausted its output budget before answering"
            );
        }
        llama_sampler_free(sampler);
        sampler = nullptr;
        llama_free(context);
        context = nullptr;
        return to_java_utf8(
            env,
            finish_result(
                stopped ? "stop" : "length",
                static_cast<int32_t>(prompt_tokens.size()),
                output_tokens,
                dropped_messages
            )
        );
    } catch (const Cancelled &) {
        if (sampler) llama_sampler_free(sampler);
        if (context) llama_free(context);
        return to_java_utf8(env, finish_result("cancelled", 0, 0, 0));
    } catch (const std::exception & error) {
        if (sampler) llama_sampler_free(sampler);
        if (context) llama_free(context);
        throw_java(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_source_client_ai_LlamaCppNative_cancelNative(
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring run_id_value
) {
    auto * engine = reinterpret_cast<SourceAiEngine *>(handle);
    if (!engine) return;
    const char * run_chars = env->GetStringUTFChars(run_id_value, nullptr);
    std::string run_id(run_chars);
    env->ReleaseStringUTFChars(run_id_value, run_chars);
    std::lock_guard<std::mutex> lock(engine->state_mutex);
    if (engine->active_run_id == run_id) engine->cancel_requested.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_source_client_ai_LlamaCppNative_destroyNative(
    JNIEnv *,
    jobject,
    jlong handle
) {
    auto * engine = reinterpret_cast<SourceAiEngine *>(handle);
    if (!engine) return;
    engine->cancel_requested.store(true);
    std::lock_guard<std::mutex> lock(engine->generation_mutex);
    delete engine;
}
