#pragma once

#include <string>

namespace nxsync {

struct Config {
    std::string client_id;
    std::string client_secret;
    std::string refresh_token;
    std::string folder_id;
};

// Reads /config/savesync/config.json. Secrets are never written to logs.
bool load_config(const char* path, Config& out, std::string& error);

} // namespace nxsync

