#include "drive_client.hpp"

#include <curl/curl.h>

#include <cctype>
#include <cstdlib>
#include <cstdio>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <sys/stat.h>

namespace {

constexpr const char* kDriveApi = "https://www.googleapis.com/drive/v3";
constexpr const char* kDriveUpload = "https://www.googleapis.com/upload/drive/v3";

size_t append_string(char* data, size_t size, size_t count, void* user) {
    const size_t bytes = size * count;
    static_cast<std::string*>(user)->append(data, bytes);
    return bytes;
}

size_t write_file(char* data, size_t size, size_t count, void* user) {
    return std::fwrite(data, size, count, static_cast<FILE*>(user));
}

size_t read_file(char* data, size_t size, size_t count, void* user) {
    return std::fread(data, size, count, static_cast<FILE*>(user));
}

size_t capture_location(char* data, size_t size, size_t count, void* user) {
    const size_t bytes = size * count;
    std::string header(data, bytes);
    const std::string prefix = "location:";
    if (header.size() >= prefix.size()) {
        std::string lower = header;
        for (char& ch : lower) ch = static_cast<char>(std::tolower(ch));
        if (lower.rfind(prefix, 0) == 0) {
            std::string value = header.substr(prefix.size());
            while (!value.empty() && (value.front() == ' ' || value.front() == '\t')) value.erase(0, 1);
            while (!value.empty() && (value.back() == '\r' || value.back() == '\n')) value.pop_back();
            *static_cast<std::string*>(user) = value;
        }
    }
    return bytes;
}

std::string json_escape(const std::string& input) {
    std::string output;
    for (const char ch : input) {
        if (ch == '"' || ch == '\\') output.push_back('\\');
        output.push_back(ch);
    }
    return output;
}

bool json_string_after(
    const std::string& document,
    const std::string& key,
    std::string& value,
    std::size_t start = 0) {
    std::size_t cursor = document.find("\"" + key + "\"", start);
    if (cursor == std::string::npos) return false;
    cursor = document.find(':', cursor);
    if (cursor == std::string::npos) return false;
    cursor = document.find('"', cursor);
    if (cursor == std::string::npos) return false;
    const std::size_t end = document.find('"', cursor + 1);
    if (end == std::string::npos) return false;
    value = document.substr(cursor + 1, end - cursor - 1);
    return true;
}

bool perform(CURL* curl, long& status, std::string& error) {
    const CURLcode code = curl_easy_perform(curl);
    if (code != CURLE_OK) {
        error = curl_easy_strerror(code);
        return false;
    }
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
    if (status < 200 || status >= 300) {
        error = "Google Drive HTTP " + std::to_string(status);
        return false;
    }
    return true;
}

std::string title_name(std::uint64_t title_id) {
    std::ostringstream name;
    name << std::uppercase << std::hex << std::setw(16) << std::setfill('0')
         << title_id << ".zip";
    return name.str();
}

std::string bearer(const std::string& token) {
    return "Authorization: Bearer " + token;
}

} // namespace

namespace nxsync {

DriveClient::DriveClient(const Config& config) : config_(config) {}

bool DriveClient::refresh_access_token(std::string& error) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        error = "cannot initialize HTTPS";
        return false;
    }
    char* client = curl_easy_escape(curl, config_.client_id.c_str(), 0);
    char* refresh = curl_easy_escape(curl, config_.refresh_token.c_str(), 0);
    char* secret = curl_easy_escape(curl, config_.client_secret.c_str(), 0);
    std::string body =
        "client_id=" + std::string(client ? client : "") +
        "&refresh_token=" + std::string(refresh ? refresh : "") +
        "&grant_type=refresh_token";
    if (!config_.client_secret.empty()) body += "&client_secret=" + std::string(secret ? secret : "");
    curl_free(client);
    curl_free(refresh);
    curl_free(secret);

    std::string response;
    curl_easy_setopt(curl, CURLOPT_URL, "https://oauth2.googleapis.com/token");
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body.c_str());
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, append_string);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 15L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30L);
    long status = 0;
    const bool ok = perform(curl, status, error);
    curl_easy_cleanup(curl);
    if (!ok) return false;
    if (!json_string_after(response, "access_token", access_token_)) {
        error = "OAuth response has no access token";
        return false;
    }
    return true;
}

bool DriveClient::find_save(
    std::uint64_t title_id,
    RemoteSave& out,
    std::string& error) {
    // A sysmodule may run for days. Refresh before each sync transaction so a
    // cached one-hour access token can never strand later uploads.
    if (!refresh_access_token(error)) return false;
    CURL* curl = curl_easy_init();
    if (!curl) {
        error = "cannot initialize HTTPS";
        return false;
    }
    std::string query = "name = '" + title_name(title_id) + "' and trashed = false";
    if (!config_.folder_id.empty()) query += " and '" + config_.folder_id + "' in parents";
    char* encoded = curl_easy_escape(curl, query.c_str(), 0);
    const std::string endpoint = std::string(kDriveApi) +
        "/files?pageSize=1&spaces=drive&fields=files(id,appProperties)&q=" +
        (encoded ? encoded : "");
    curl_free(encoded);
    std::string response;
    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, bearer(access_token_).c_str());
    curl_easy_setopt(curl, CURLOPT_URL, endpoint.c_str());
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, append_string);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30L);
    long status = 0;
    const bool ok = perform(curl, status, error);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    if (!ok) return false;

    out = RemoteSave {};
    if (!json_string_after(response, "id", out.id)) return true;
    out.exists = true;
    json_string_after(response, "nxsync_sha256", out.sha256);
    std::string modified;
    if (json_string_after(response, "nxsync_source_modified_unix", modified)) {
        char* end = nullptr;
        out.source_modified_unix = std::strtoll(modified.c_str(), &end, 10);
        if (!end || *end != '\0') out.source_modified_unix = 0;
    }
    return true;
}

bool DriveClient::upload_if_newer(
    std::uint64_t title_id,
    const char* zip_path,
    const std::string& sha256,
    std::int64_t modified_unix,
    std::string& error) {
    RemoteSave remote;
    if (!find_save(title_id, remote, error)) return false;
    if (remote.exists && remote.sha256 == sha256) return true;
    if (remote.exists && modified_unix <= remote.source_modified_unix) return true;

    const std::string name = title_name(title_id);
    std::ostringstream metadata;
    metadata << "{\"name\":\"" << json_escape(name) << "\",\"appProperties\":{"
             << "\"nxsync_sha256\":\"" << json_escape(sha256) << "\","
             << "\"nxsync_source_modified_unix\":\"" << modified_unix << "\","
             << "\"nxsync_title_id\":\"" << name.substr(0, 16) << "\"}";
    if (!remote.exists && !config_.folder_id.empty()) {
        metadata << ",\"parents\":[\"" << json_escape(config_.folder_id) << "\"]";
    }
    metadata << "}";

    const std::string endpoint = remote.exists
        ? std::string(kDriveUpload) + "/files/" + remote.id + "?uploadType=resumable"
        : std::string(kDriveUpload) + "/files?uploadType=resumable";
    std::string location;
    if (!begin_resumable_upload(
            remote.exists ? "PATCH" : "POST",
            endpoint,
            metadata.str(),
            location,
            error)) return false;
    return put_file(location, zip_path, error);
}

bool DriveClient::begin_resumable_upload(
    const char* method,
    const std::string& endpoint,
    const std::string& metadata,
    std::string& location,
    std::string& error) {
    CURL* curl = curl_easy_init();
    if (!curl) {
        error = "cannot initialize HTTPS";
        return false;
    }
    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, bearer(access_token_).c_str());
    headers = curl_slist_append(headers, "Content-Type: application/json; charset=UTF-8");
    headers = curl_slist_append(headers, "X-Upload-Content-Type: application/zip");
    curl_easy_setopt(curl, CURLOPT_URL, endpoint.c_str());
    curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, method);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, metadata.c_str());
    curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, capture_location);
    curl_easy_setopt(curl, CURLOPT_HEADERDATA, &location);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, append_string);
    std::string response;
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30L);
    long status = 0;
    bool ok = perform(curl, status, error);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    if (ok && location.empty()) {
        error = "Drive did not return an upload location";
        ok = false;
    }
    return ok;
}

bool DriveClient::put_file(
    const std::string& location,
    const char* path,
    std::string& error) {
    struct stat info {};
    if (stat(path, &info) != 0) {
        error = "cannot inspect ZIP";
        return false;
    }
    FILE* input = std::fopen(path, "rb");
    if (!input) {
        error = "cannot open ZIP";
        return false;
    }
    CURL* curl = curl_easy_init();
    if (!curl) {
        std::fclose(input);
        error = "cannot initialize HTTPS";
        return false;
    }
    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, bearer(access_token_).c_str());
    headers = curl_slist_append(headers, "Content-Type: application/zip");
    curl_easy_setopt(curl, CURLOPT_URL, location.c_str());
    curl_easy_setopt(curl, CURLOPT_UPLOAD, 1L);
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_READFUNCTION, read_file);
    curl_easy_setopt(curl, CURLOPT_READDATA, input);
    curl_easy_setopt(curl, CURLOPT_INFILESIZE_LARGE, static_cast<curl_off_t>(info.st_size));
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, append_string);
    std::string response;
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 300L);
    long status = 0;
    const bool ok = perform(curl, status, error);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    std::fclose(input);
    return ok;
}

bool DriveClient::download(
    std::uint64_t title_id,
    const char* path,
    std::string& error) {
    RemoteSave remote;
    if (!find_save(title_id, remote, error)) return false;
    if (!remote.exists) {
        error = "no remote save exists for this title";
        return false;
    }
    FILE* output = std::fopen(path, "wb");
    if (!output) {
        error = "cannot create download on SD";
        return false;
    }
    CURL* curl = curl_easy_init();
    if (!curl) {
        std::fclose(output);
        error = "cannot initialize HTTPS";
        return false;
    }
    struct curl_slist* headers = nullptr;
    headers = curl_slist_append(headers, bearer(access_token_).c_str());
    const std::string endpoint = std::string(kDriveApi) + "/files/" + remote.id + "?alt=media";
    curl_easy_setopt(curl, CURLOPT_URL, endpoint.c_str());
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, write_file);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, output);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 300L);
    long status = 0;
    const bool ok = perform(curl, status, error);
    curl_slist_free_all(headers);
    curl_easy_cleanup(curl);
    std::fclose(output);
    if (!ok) std::remove(path);
    return ok;
}

} // namespace nxsync
