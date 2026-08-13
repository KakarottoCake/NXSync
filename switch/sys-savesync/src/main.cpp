#include "config.hpp"
#include "drive_client.hpp"
#include "save_archive.hpp"
#include "status.hpp"

#include <switch.h>
#include <curl/curl.h>

#include <atomic>
#include <cstdio>
#include <ctime>
#include <fstream>
#include <iomanip>
#include <memory>
#include <sstream>
#include <string>
#include <sys/stat.h>

namespace {

constexpr const char* kConfigDirectory = "sdmc:/config/savesync";
constexpr const char* kConfigPath = "sdmc:/config/savesync/config.json";
constexpr const char* kStatusPath = "sdmc:/config/savesync/status.json";
constexpr const char* kCommandPath = "sdmc:/config/savesync/command.json";

struct ApplicationTracker {
    u64 process_id{0};
    u64 title_id{0};
    u64 last_title_id{0};
    u64 pending_push{0};
    u64 pending_pull{0};
};

std::string title_hex(u64 title_id) {
    std::ostringstream output;
    output << std::uppercase << std::hex << std::setw(16)
           << std::setfill('0') << title_id;
    return output.str();
}

bool internet_connected() {
    NifmInternetConnectionType type {};
    NifmInternetConnectionStatus status {};
    u32 strength = 0;
    const Result result = nifmGetInternetConnectionStatus(&type, &strength, &status);
    return R_SUCCEEDED(result) &&
           status == NifmInternetConnectionStatus_Connected;
}

void discover_current_application(ApplicationTracker& tracker) {
    u64 pid = 0;
    u64 title = 0;
    if (R_SUCCEEDED(pmdmntGetApplicationProcessId(&pid)) &&
        R_SUCCEEDED(pminfoGetProgramId(&title, pid))) {
        tracker.process_id = pid;
        tracker.title_id = title;
        tracker.last_title_id = title;
    }
}

void process_pm_event(Event& event, ApplicationTracker& tracker) {
    if (R_FAILED(eventWait(&event, 0))) return;
    PmProcessEventInfo info {};
    if (R_FAILED(pmshellGetProcessEventInfo(&info))) return;
    if (info.event == PmProcessEvent_Start) {
        discover_current_application(tracker);
        return;
    }
    if (info.event == PmProcessEvent_Exit &&
        tracker.process_id != 0 &&
        info.process_id == tracker.process_id) {
        // Preserve the Title ID before the foreground application becomes 0.
        tracker.last_title_id = tracker.title_id;
        tracker.process_id = 0;
        tracker.title_id = 0;
        if (tracker.title_id == 0) {
            tracker.pending_push = tracker.last_title_id;
        }
    }
}

void read_overlay_command(ApplicationTracker& tracker) {
    std::ifstream input(kCommandPath);
    if (!input) return;
    std::string command(
        (std::istreambuf_iterator<char>(input)),
        std::istreambuf_iterator<char>());
    input.close();
    std::remove(kCommandPath); // Consume commands exactly once.
    const u64 target = tracker.title_id != 0
        ? tracker.title_id
        : tracker.last_title_id;
    if (command.find("\"action\":\"sync\"") != std::string::npos) {
        tracker.pending_push = target;
    } else if (command.find("\"action\":\"pull\"") != std::string::npos) {
        // Pulls requested for a running title are deferred until its process
        // exits; mounting and mutating a live save is unsafe.
        tracker.pending_pull = target;
    }
}

bool push(
    nxsync::DriveClient& drive,
    u64 title_id,
    nxsync::Status& status) {
    status.state = nxsync::SyncState::syncing;
    status.detail = "Backing up " + title_hex(title_id);
    nxsync::write_status(kStatusPath, status);
    const std::string zip =
        std::string(kConfigDirectory) + "/" + title_hex(title_id) + ".zip";
    nxsync::ArchiveInfo archive;
    std::string error;
    if (!nxsync::dump_save(title_id, zip.c_str(), archive, error) ||
        !drive.upload_if_newer(
            title_id,
            zip.c_str(),
            archive.sha256,
            archive.modified_unix,
            error)) {
        status.state = nxsync::SyncState::error;
        status.detail = error;
        return false;
    }
    std::remove(zip.c_str());
    status.state = nxsync::SyncState::idle;
    status.last_sync_unix = std::time(nullptr);
    status.detail = "Backup complete";
    return true;
}

bool pull(
    nxsync::DriveClient& drive,
    u64 title_id,
    nxsync::Status& status) {
    status.state = nxsync::SyncState::syncing;
    status.detail = "Downloading " + title_hex(title_id);
    nxsync::write_status(kStatusPath, status);
    const std::string zip =
        std::string(kConfigDirectory) + "/pull-" + title_hex(title_id) + ".zip";
    const std::string rollback =
        std::string(kConfigDirectory) + "/rollback-" + title_hex(title_id) + ".zip";
    std::string error;
    nxsync::ArchiveInfo rollback_info;
    // Never mutate a console save unless the original has first been captured.
    // The most recent rollback archive is deliberately retained on the SD card.
    if (!nxsync::dump_save(
            title_id, rollback.c_str(), rollback_info, error)) {
        status.state = nxsync::SyncState::error;
        status.detail = "Cannot create rollback: " + error;
        return false;
    }
    if (!drive.download(title_id, zip.c_str(), error)) {
        status.state = nxsync::SyncState::error;
        status.detail = error;
        return false;
    }
    if (!nxsync::restore_save(title_id, zip.c_str(), error)) {
        const std::string restore_error = error;
        std::string rollback_error;
        if (!nxsync::restore_save(
                title_id, rollback.c_str(), rollback_error)) {
            status.state = nxsync::SyncState::error;
            status.detail = "Restore failed: " + restore_error +
                "; rollback also failed: " + rollback_error;
            return false;
        }
        status.state = nxsync::SyncState::error;
        status.detail = "Restore failed and was rolled back: " + restore_error;
        return false;
    }
    std::remove(zip.c_str());
    status.state = nxsync::SyncState::idle;
    status.last_sync_unix = std::time(nullptr);
    status.detail = "Restore complete";
    return true;
}

} // namespace

int main(int, char**) {
    mkdir(kConfigDirectory, 0700);
    nxsync::Status status;
    nxsync::Config config;
    std::string error;
    if (!nxsync::load_config(kConfigPath, config, error)) {
        status.state = nxsync::SyncState::error;
        status.detail = error;
        nxsync::write_status(kStatusPath, status);
        return 1;
    }

    ApplicationTracker tracker;
    discover_current_application(tracker);
    Event process_event {};
    if (R_FAILED(pmshellGetProcessEventHandle(&process_event))) {
        status.state = nxsync::SyncState::error;
        status.detail = "pm:shell event access denied";
        nxsync::write_status(kStatusPath, status);
        return 2;
    }

    nxsync::DriveClient drive(config);
    status.state = internet_connected()
        ? nxsync::SyncState::idle
        : nxsync::SyncState::offline;
    status.detail = "Ready";

    // This is a boot sysmodule (AppletType_None), not an applet with a message
    // queue. Its worker loop intentionally lives until Horizon terminates it.
    while (true) {
        process_pm_event(process_event, tracker);
        read_overlay_command(tracker);
        status.active_title_id = tracker.title_id;
        status.last_title_id = tracker.last_title_id;

        if (!internet_connected()) {
            status.state = nxsync::SyncState::offline;
            status.detail = "Waiting for a network connection";
            nxsync::write_status(kStatusPath, status);
            svcSleepThread(5'000'000'000ULL);
            continue;
        }
        if (tracker.pending_push != 0) {
            const u64 title = tracker.pending_push;
            if (push(drive, title, status)) tracker.pending_push = 0;
        }
        if (tracker.pending_pull != 0 && tracker.title_id == 0) {
            const u64 title = tracker.pending_pull;
            if (pull(drive, title, status)) tracker.pending_pull = 0;
        }
        if (status.state == nxsync::SyncState::offline) {
            status.state = nxsync::SyncState::idle;
            status.detail = "Ready";
        }
        nxsync::write_status(kStatusPath, status);
        svcSleepThread(1'000'000'000ULL);
    }
    eventClose(&process_event);
    return 0;
}

// A boot sysmodule has no applet services. Initialize only the services used
// above, and tear them down in reverse order.
extern "C" {
u32 __nx_applet_type = AppletType_None;
u32 __nx_fs_num_sessions = 2;

void __appInit(void) {
    smInitialize();
    fsInitialize();
    fsdevMountSdmc();
    accountInitialize(AccountServiceType_System);
    nifmInitialize(NifmServiceType_System);
    pmdmntInitialize();
    pminfoInitialize();
    pmshellInitialize();
    socketInitializeDefault();
    curl_global_init(CURL_GLOBAL_DEFAULT);
}

void __appExit(void) {
    curl_global_cleanup();
    socketExit();
    pmshellExit();
    pminfoExit();
    pmdmntExit();
    nifmExit();
    accountExit();
    fsdevUnmountAll();
    fsExit();
    smExit();
}
}
