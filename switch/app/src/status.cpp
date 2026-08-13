#include "status.hpp"

#include <cstdio>
#include <fstream>
#include <iomanip>

namespace {

const char* state_name(nxsync::SyncState state) {
    switch (state) {
        case nxsync::SyncState::idle: return "Idle";
        case nxsync::SyncState::syncing: return "Syncing";
        case nxsync::SyncState::offline: return "Offline";
        default: return "Error";
    }
}

std::string escape_json(const std::string& input) {
    std::string output;
    for (const char ch : input) {
        if (ch == '"' || ch == '\\') output.push_back('\\');
        if (ch >= 0x20) output.push_back(ch);
    }
    return output;
}

} // namespace

namespace nxsync {

bool write_status(const char* path, const Status& status) {
    const std::string temporary = std::string(path) + ".tmp";
    std::ofstream output(temporary, std::ios::trunc);
    if (!output) return false;
    output << "{\"state\":\"" << state_name(status.state)
           << "\",\"active_title_id\":\""
           << std::hex << std::setw(16) << std::setfill('0')
           << status.active_title_id
           << "\",\"last_title_id\":\""
           << std::setw(16) << status.last_title_id
           << "\",\"last_sync_unix\":" << std::dec << status.last_sync_unix
           << ",\"detail\":\"" << escape_json(status.detail) << "\"}\n";
    output.close();
    if (!output) return false;
    std::remove(path);
    return std::rename(temporary.c_str(), path) == 0;
}

} // namespace nxsync

