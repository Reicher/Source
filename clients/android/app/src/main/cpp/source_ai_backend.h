#pragma once

#include <mutex>

#include "llama.h"

inline void source_ai_backend_init() {
    static std::once_flag initialized;
    std::call_once(initialized, [] { llama_backend_init(); });
}
