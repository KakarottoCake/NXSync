#include "save_archive.hpp"

#include <switch.h>
#include <mbedtls/sha256.h>
#include <minizip/unzip.h>
#include <minizip/zip.h>

#include <array>
#include <cerrno>
#include <ctime>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace {

bool add_tree(
    zipFile archive,
    const std::string& root,
    const std::string& relative,
    std::int64_t& modified,
    std::string& error) {
    const std::string directory = relative.empty() ? root : root + "/" + relative;
    DIR* handle = opendir(directory.c_str());
    if (!handle) {
        error = "cannot open mounted save directory";
        return false;
    }
    bool ok = true;
    while (ok) {
        dirent* entry = readdir(handle);
        if (!entry) break;
        if (!std::strcmp(entry->d_name, ".") || !std::strcmp(entry->d_name, "..")) continue;
        const std::string child_relative =
            relative.empty() ? entry->d_name : relative + "/" + entry->d_name;
        const std::string child_path = root + "/" + child_relative;
        struct stat info {};
        if (lstat(child_path.c_str(), &info) != 0) {
            error = "cannot inspect save entry";
            ok = false;
            break;
        }
        if (S_ISLNK(info.st_mode)) continue;
        if (info.st_mtime > modified) modified = info.st_mtime;
        if (S_ISDIR(info.st_mode)) {
            ok = add_tree(archive, root, child_relative, modified, error);
            continue;
        }
        if (!S_ISREG(info.st_mode)) continue;

        zip_fileinfo zip_info {};
        if (zipOpenNewFileInZip64(
                archive,
                child_relative.c_str(),
                &zip_info,
                nullptr, 0, nullptr, 0, nullptr,
                Z_DEFLATED,
                Z_DEFAULT_COMPRESSION,
                1) != ZIP_OK) {
            error = "cannot add file to save ZIP";
            ok = false;
            break;
        }
        std::ifstream input(child_path, std::ios::binary);
        std::array<char, 64 * 1024> buffer {};
        while (input) {
            input.read(buffer.data(), buffer.size());
            const std::streamsize count = input.gcount();
            if (count > 0 &&
                zipWriteInFileInZip(archive, buffer.data(), count) != ZIP_OK) {
                error = "cannot write save ZIP";
                ok = false;
                break;
            }
        }
        if (zipCloseFileInZip(archive) != ZIP_OK) {
            error = "cannot finalize file in save ZIP";
            ok = false;
        }
    }
    closedir(handle);
    return ok;
}

std::string hex_digest(const unsigned char* digest, std::size_t size) {
    std::ostringstream result;
    result << std::hex << std::setfill('0');
    for (std::size_t i = 0; i < size; ++i) result << std::setw(2) << unsigned(digest[i]);
    return result.str();
}

bool sha256_file(const char* path, std::string& output, std::string& error) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        error = "cannot reopen save ZIP";
        return false;
    }
    mbedtls_sha256_context context;
    mbedtls_sha256_init(&context);
    if (mbedtls_sha256_starts_ret(&context, 0) != 0) {
        mbedtls_sha256_free(&context);
        error = "SHA-256 initialization failed";
        return false;
    }
    std::array<unsigned char, 64 * 1024> buffer {};
    while (input) {
        input.read(reinterpret_cast<char*>(buffer.data()), buffer.size());
        const std::streamsize count = input.gcount();
        if (count > 0 &&
            mbedtls_sha256_update_ret(&context, buffer.data(), count) != 0) {
            mbedtls_sha256_free(&context);
            error = "SHA-256 update failed";
            return false;
        }
    }
    unsigned char digest[32] {};
    const int result = mbedtls_sha256_finish_ret(&context, digest);
    mbedtls_sha256_free(&context);
    if (result != 0) {
        error = "SHA-256 finalization failed";
        return false;
    }
    output = hex_digest(digest, sizeof(digest));
    return true;
}

bool safe_zip_name(const std::string& name) {
    if (name.empty() || name[0] == '/' || name[0] == '\\') return false;
    if (name.find('\\') != std::string::npos) return false;
    if (name == ".." || name.rfind("../", 0) == 0 ||
        name.find("/../") != std::string::npos) return false;
    return true;
}

bool mount_save_read_only(u64 title_id, std::string& error) {
    // 1. Try last opened user
    AccountUid last_uid {};
    if (R_SUCCEEDED(accountGetLastOpenedUser(&last_uid))) {
        if (R_SUCCEEDED(fsdevMountSaveDataReadOnly("save", title_id, last_uid))) {
            return true;
        }
    }

    // 2. Try all registered users on system
    AccountUid uids[8] {};
    s32 user_count = 0;
    if (R_SUCCEEDED(accountListAllUsers(uids, 8, &user_count))) {
        for (s32 i = 0; i < user_count; i++) {
            if (R_SUCCEEDED(fsdevMountSaveDataReadOnly("save", title_id, uids[i]))) {
                return true;
            }
        }
    }

    // 3. Try Device save data (e.g. Animal Crossing, Pokemon, system saves)
    if (R_SUCCEEDED(fsdevMountDeviceSaveData("save", title_id))) {
        return true;
    }

    error = "cannot mount save for any user account or device";
    return false;
}

bool mount_save_read_write(u64 title_id, std::string& error) {
    // 1. Try last opened user
    AccountUid last_uid {};
    if (R_SUCCEEDED(accountGetLastOpenedUser(&last_uid))) {
        if (R_SUCCEEDED(fsdevMountSaveData("save", title_id, last_uid))) {
            return true;
        }
    }

    // 2. Try all registered users
    AccountUid uids[8] {};
    s32 user_count = 0;
    if (R_SUCCEEDED(accountListAllUsers(uids, 8, &user_count))) {
        for (s32 i = 0; i < user_count; i++) {
            if (R_SUCCEEDED(fsdevMountSaveData("save", title_id, uids[i]))) {
                return true;
            }
        }
    }

    // 3. Try Device save data
    if (R_SUCCEEDED(fsdevMountDeviceSaveData("save", title_id))) {
        return true;
    }

    error = "cannot mount save read-write for any account";
    return false;
}

} // namespace

namespace nxsync {

bool dump_save(
    std::uint64_t title_id,
    const char* zip_path,
    ArchiveInfo& out,
    std::string& error) {
    if (!mount_save_read_only(title_id, error)) {
        return false;
    }
    zipFile archive = zipOpen64(zip_path, APPEND_STATUS_CREATE);
    if (!archive) {
        fsdevUnmountDevice("save");
        error = "cannot create ZIP on the SD card";
        return false;
    }
    out.modified_unix = 0;
    bool ok = add_tree(archive, "save:", "", out.modified_unix, error);
    if (zipClose(archive, nullptr) != ZIP_OK && ok) {
        error = "cannot finalize ZIP";
        ok = false;
    }
    fsdevUnmountDevice("save");
    if (out.modified_unix <= 0) out.modified_unix = std::time(nullptr);
    return ok && sha256_file(zip_path, out.sha256, error);
}

bool restore_save(
    std::uint64_t title_id,
    const char* zip_path,
    std::string& error) {
    if (!mount_save_read_write(title_id, error)) {
        return false;
    }
    unzFile archive = unzOpen64(zip_path);
    if (!archive) {
        fsdevUnmountDevice("save");
        error = "downloaded save is not a ZIP";
        return false;
    }
    bool ok = unzGoToFirstFile(archive) == UNZ_OK;
    while (ok) {
        std::array<char, 1024> name_buffer {};
        unz_file_info64 info {};
        if (unzGetCurrentFileInfo64(
                archive, &info, name_buffer.data(), name_buffer.size(),
                nullptr, 0, nullptr, 0) != UNZ_OK) {
            error = "cannot read ZIP entry";
            ok = false;
            break;
        }
        const std::string name(name_buffer.data());
        if (!safe_zip_name(name)) {
            error = "unsafe path in downloaded ZIP";
            ok = false;
            break;
        }
        const std::string target = "save:/" + name;
        if (!name.empty() && name.back() == '/') {
            mkdir(target.c_str(), 0700);
        } else {
            std::size_t slash = 0;
            while ((slash = target.find('/', slash + 1)) != std::string::npos) {
                mkdir(target.substr(0, slash).c_str(), 0700);
            }
            if (unzOpenCurrentFile(archive) != UNZ_OK) {
                error = "cannot open ZIP entry";
                ok = false;
                break;
            }
            std::ofstream output(target, std::ios::binary | std::ios::trunc);
            std::array<char, 64 * 1024> buffer {};
            int count = 0;
            while (output && (count = unzReadCurrentFile(
                       archive, buffer.data(), buffer.size())) > 0) {
                output.write(buffer.data(), count);
            }
            output.close();
            unzCloseCurrentFile(archive);
            if (!output || count < 0) {
                error = "cannot restore save file";
                ok = false;
                break;
            }
        }
        const int next = unzGoToNextFile(archive);
        if (next == UNZ_END_OF_LIST_OF_FILE) break;
        if (next != UNZ_OK) {
            error = "cannot advance through save ZIP";
            ok = false;
            break;
        }
    }
    unzClose(archive);
    if (ok && R_FAILED(fsdevCommitDevice("save"))) {
        error = "cannot commit restored save";
        ok = false;
    }
    fsdevUnmountDevice("save");
    return ok;
}

} // namespace nxsync
