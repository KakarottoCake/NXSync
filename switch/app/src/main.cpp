#include "config.hpp"
#include "drive_client.hpp"
#include "save_archive.hpp"
#include "status.hpp"

#include <switch.h>
#include <curl/curl.h>

#include <algorithm>
#include <cstdio>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <vector>

namespace {

constexpr const char* kConfigDirectory = "sdmc:/config/savesync";
constexpr const char* kConfigPath = "sdmc:/config/savesync/config.json";

PadState padState;

std::string title_hex(u64 title_id) {
    std::ostringstream output;
    output << std::uppercase << std::hex << std::setw(16)
           << std::setfill('0') << title_id;
    return output.str();
}

bool is_internet_connected() {
    NifmInternetConnectionType type {};
    NifmInternetConnectionStatus status {};
    u32 strength = 0;
    const Result result = nifmGetInternetConnectionStatus(&type, &strength, &status);
    return R_SUCCEEDED(result) &&
           status == NifmInternetConnectionStatus_Connected;
}

std::vector<u64> get_user_title_ids() {
    std::vector<u64> titles;
    FsSaveDataInfoReader reader {};
    if (R_FAILED(fsOpenSaveDataInfoReader(&reader, FsSaveDataSpaceId_User))) {
        return titles;
    }
    
    FsSaveDataInfo info {};
    s64 total = 0;
    while (R_SUCCEEDED(fsSaveDataInfoReaderRead(&reader, &info, 1, &total)) && total > 0) {
        if (info.save_data_type == FsSaveDataType_Account && info.application_id != 0) {
            if (std::find(titles.begin(), titles.end(), info.application_id) == titles.end()) {
                titles.push_back(info.application_id);
            }
        }
    }
    fsSaveDataInfoReaderClose(&reader);
    return titles;
}

void print_header() {
    consoleClear();
    std::cout << "\x1b[1;36m==================================================================\x1b[0m\n";
    std::cout << "\x1b[1;37m                        NXSync Save Synchronizer                   \x1b[0m\n";
    std::cout << "\x1b[1;36m==================================================================\x1b[0m\n\n";
    if (appletGetAppletType() == AppletType_LibraryApplet || appletGetAppletType() == AppletType_OverlayApplet) {
        std::cout << "\x1b[1;33m[TIP] Running in Applet Mode (Album applet).\x1b[0m\n";
        std::cout << "For full RAM & save access, open hbmenu by holding (R) while launching a game!\n\n";
    }
}

void wait_for_exit() {
    std::cout << "\n\x1b[1;33m------------------------------------------------------------------\x1b[0m\n";
    std::cout << "\x1b[1;32mSync operation finished. Press (+) or (A) to return to Homebrew Menu.\x1b[0m\n";
    while (appletMainLoop()) {
        padUpdate(&padState);
        u64 kDown = padGetButtonsDown(&padState);
        if (kDown & (HidNpadButton_Plus | HidNpadButton_A | HidNpadButton_B)) break;
        consoleUpdate(NULL);
    }
}

} // namespace

int main(int argc, char** argv) {
    consoleInit(NULL);
    padConfigureInput(1, HidNpadStyleSet_NpadStandard);
    padInitializeDefault(&padState);
    
    print_header();

    mkdir(kConfigDirectory, 0700);

    // Initialize Network Sockets for curl
    socketInitializeDefault();
    nifmInitialize(NifmServiceType_User);
    accountInitialize(AccountServiceType_Administrator);

    std::cout << "[INFO] Loading NXSync configuration...\n";
    nxsync::Config config;
    std::string error;
    if (!nxsync::load_config(kConfigPath, config, error)) {
        std::cout << "\x1b[1;31m[ERROR] Failed to load config.json:\x1b[0m " << error << "\n";
        std::cout << "Please ensure /config/savesync/config.json is configured correctly.\n";
        wait_for_exit();
        socketExit();
        nifmExit();
        accountExit();
        consoleExit(NULL);
        return 1;
    }

    std::cout << "\x1b[1;32m[OK]\x1b[0m Config loaded. Checking internet connection...\n";
    if (!is_internet_connected()) {
        std::cout << "\x1b[1;31m[ERROR] No active internet connection found!\x1b[0m\n";
        std::cout << "Please connect your Nintendo Switch to Wi-Fi and try again.\n";
        wait_for_exit();
        socketExit();
        nifmExit();
        accountExit();
        consoleExit(NULL);
        return 1;
    }

    std::cout << "\x1b[1;32m[OK]\x1b[0m Connected to Wi-Fi. Authenticating with Google Drive...\n";
    nxsync::DriveClient drive(config);
    if (!drive.refresh_access_token(error)) {
        std::cout << "\x1b[1;31m[ERROR] Google OAuth authentication failed:\x1b[0m " << error << "\n";
        wait_for_exit();
        socketExit();
        nifmExit();
        accountExit();
        consoleExit(NULL);
        return 1;
    }
    std::cout << "\x1b[1;32m[OK]\x1b[0m Google Drive authenticated successfully.\n\n";

    std::cout << "[INFO] Discovering game save files on system...\n";
    std::vector<u64> titles = get_user_title_ids();
    if (titles.empty()) {
        std::cout << "\x1b[1;33m[WARNING] No active user game saves found on system.\x1b[0m\n";
    } else {
        std::cout << "\x1b[1;32m[OK]\x1b[0m Found " << titles.size() << " game save(s) to process.\n\n";
    }

    int synced_count = 0;
    int uploaded_count = 0;
    int downloaded_count = 0;
    int skipped_count = 0;

    for (std::size_t i = 0; i < titles.size(); ++i) {
        const u64 title_id = titles[i];
        const std::string title_str = title_hex(title_id);
        std::cout << "\x1b[1;37m[" << (i + 1) << "/" << titles.size() << "] Title ID: " << title_str << "\x1b[0m\n";

        // Dump local save to temporary archive to compute hash & timestamp
        const std::string local_zip = std::string(kConfigDirectory) + "/" + title_str + ".zip";
        nxsync::ArchiveInfo local_archive;
        if (!nxsync::dump_save(title_id, local_zip.c_str(), local_archive, error)) {
            std::cout << "  \x1b[1;33m-> Skipped (Cannot mount save: " << error << ")\x1b[0m\n";
            skipped_count++;
            continue;
        }

        // Check remote save on Google Drive
        nxsync::RemoteSave remote;
        std::string find_err;
        bool has_remote = drive.find_save(title_id, remote, find_err);

        if (!has_remote) {
            // No remote save exists yet: upload local save
            std::cout << "  -> Remote save not found on Google Drive. Uploading...\n";
            if (drive.upload_if_newer(title_id, local_zip.c_str(), local_archive.sha256, local_archive.modified_unix, error)) {
                std::cout << "  \x1b[1;32m-> [UPLOADED] Saved to Google Drive!\x1b[0m\n";
                uploaded_count++;
            } else {
                std::cout << "  \x1b[1;31m-> Upload failed:\x1b[0m " << error << "\n";
            }
        } else if (remote.sha256 == local_archive.sha256) {
            std::cout << "  \x1b[1;36m-> [IN SYNC] Local and remote saves match (SHA256 identical).\x1b[0m\n";
            synced_count++;
        } else if (remote.source_modified_unix > local_archive.modified_unix) {
            // Remote save is newer: download & restore
            std::cout << "  -> Remote save is NEWER. Downloading & restoring...\n";
            const std::string pull_zip = std::string(kConfigDirectory) + "/pull-" + title_str + ".zip";
            if (drive.download(title_id, pull_zip.c_str(), error) && nxsync::restore_save(title_id, pull_zip.c_str(), error)) {
                std::cout << "  \x1b[1;32m-> [RESTORED] Updated console save from Google Drive!\x1b[0m\n";
                downloaded_count++;
            } else {
                std::cout << "  \x1b[1;31m-> Restore failed:\x1b[0m " << error << "\n";
            }
            std::remove(pull_zip.c_str());
        } else {
            // Local save is newer: upload
            std::cout << "  -> Local save is NEWER. Uploading to Google Drive...\n";
            if (drive.upload_if_newer(title_id, local_zip.c_str(), local_archive.sha256, local_archive.modified_unix, error)) {
                std::cout << "  \x1b[1;32m-> [UPLOADED] Updated Google Drive save!\x1b[0m\n";
                uploaded_count++;
            } else {
                std::cout << "  \x1b[1;31m-> Upload failed:\x1b[0m " << error << "\n";
            }
        }

        std::remove(local_zip.c_str());
        consoleUpdate(NULL);
    }

    std::cout << "\n\x1b[1;36m==================================================================\x1b[0m\n";
    std::cout << "\x1b[1;37mSUMMARY:\x1b[0m " << uploaded_count << " Uploaded | " << downloaded_count << " Restored | " << synced_count << " Up to date\n";
    std::cout << "\x1b[1;36m==================================================================\x1b[0m\n";

    wait_for_exit();

    socketExit();
    nifmExit();
    accountExit();
    consoleExit(NULL);
    return 0;
}
