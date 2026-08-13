#pragma once

#include <cstdint>
#include <string>

namespace nxsync {

enum class SyncState { idle, syncing, offline, error };

struct Status {
    SyncState state{SyncState::offline};
    std::uint64_t active_title_id{0};
    std::uint64_t last_title_id{0};
    std::int64_t last_sync_unix{0};
    std::string detail;
};

bool write_status(const char* path, const Status& status);

} // namespace nxsync

