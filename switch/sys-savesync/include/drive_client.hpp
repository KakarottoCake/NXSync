#pragma once

#include "config.hpp"

#include <cstdint>
#include <string>

namespace nxsync {

struct RemoteSave {
    std::string id;
    std::string sha256;
    std::int64_t source_modified_unix{0};
    bool exists{false};
};

class DriveClient {
public:
    explicit DriveClient(const Config& config);

    bool refresh_access_token(std::string& error);
    bool find_save(std::uint64_t title_id, RemoteSave& out, std::string& error);
    bool upload_if_newer(
        std::uint64_t title_id,
        const char* zip_path,
        const std::string& sha256,
        std::int64_t modified_unix,
        std::string& error);
    bool download(std::uint64_t title_id, const char* path, std::string& error);

private:
    Config config_;
    std::string access_token_;

    bool begin_resumable_upload(
        const char* method,
        const std::string& endpoint,
        const std::string& metadata,
        std::string& location,
        std::string& error);
    bool put_file(
        const std::string& location,
        const char* path,
        std::string& error);
};

} // namespace nxsync

