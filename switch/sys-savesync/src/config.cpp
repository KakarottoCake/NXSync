#include "config.hpp"

#include <fstream>
#include <sstream>

namespace {

bool json_string(
    const std::string& document,
    const std::string& key,
    std::string& value) {
    const std::string needle = "\"" + key + "\"";
    std::size_t cursor = document.find(needle);
    if (cursor == std::string::npos) return false;
    cursor = document.find(':', cursor + needle.size());
    if (cursor == std::string::npos) return false;
    cursor = document.find('"', cursor + 1);
    if (cursor == std::string::npos) return false;
    ++cursor;
    std::string result;
    bool escaped = false;
    for (; cursor < document.size(); ++cursor) {
        const char ch = document[cursor];
        if (escaped) {
            switch (ch) {
                case '"': result.push_back('"'); break;
                case '\\': result.push_back('\\'); break;
                case '/': result.push_back('/'); break;
                case 'n': result.push_back('\n'); break;
                case 'r': result.push_back('\r'); break;
                case 't': result.push_back('\t'); break;
                default: return false;
            }
            escaped = false;
        } else if (ch == '\\') {
            escaped = true;
        } else if (ch == '"') {
            value = result;
            return true;
        } else {
            result.push_back(ch);
        }
    }
    return false;
}

} // namespace

namespace nxsync {

bool load_config(const char* path, Config& out, std::string& error) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        error = "config.json is missing";
        return false;
    }
    std::ostringstream buffer;
    buffer << input.rdbuf();
    const std::string data = buffer.str();
    if (!json_string(data, "client_id", out.client_id) ||
        !json_string(data, "refresh_token", out.refresh_token)) {
        error = "config.json needs client_id and refresh_token";
        return false;
    }
    json_string(data, "client_secret", out.client_secret);
    json_string(data, "folder_id", out.folder_id);
    return true;
}

} // namespace nxsync

