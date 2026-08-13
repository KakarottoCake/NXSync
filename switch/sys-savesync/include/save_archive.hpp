#pragma once

#include <cstdint>
#include <string>

namespace nxsync {

struct ArchiveInfo {
    std::string sha256;
    std::int64_t modified_unix{0};
};

// Mounts the last user's account save read-only, creates a ZIP on SD, then
// unmounts. No live save filesystem is ever mutated by backup.
bool dump_save(
    std::uint64_t title_id,
    const char* zip_path,
    ArchiveInfo& out,
    std::string& error);

// Restore is intentionally allowed only after the application has exited.
bool restore_save(
    std::uint64_t title_id,
    const char* zip_path,
    std::string& error);

} // namespace nxsync

