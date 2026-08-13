#define TESLA_INIT_IMPL

#if __has_include(<ultrahand.hpp>)
#include <ultrahand.hpp>
#else
#include <tesla.hpp>
#endif

#include <cstdio>
#include <fstream>
#include <string>

namespace {

constexpr const char* kStatusPath = "sdmc:/config/savesync/status.json";
constexpr const char* kCommandPath = "sdmc:/config/savesync/command.json";

std::string json_value(const std::string& json, const std::string& key) {
    std::size_t cursor = json.find("\"" + key + "\"");
    if (cursor == std::string::npos) return {};
    cursor = json.find(':', cursor);
    if (cursor == std::string::npos) return {};
    cursor = json.find('"', cursor);
    if (cursor == std::string::npos) return {};
    const std::size_t end = json.find('"', cursor + 1);
    return end == std::string::npos
        ? std::string()
        : json.substr(cursor + 1, end - cursor - 1);
}

struct DisplayStatus {
    std::string state{"Offline"};
    std::string active_title;
    std::string last_title;
    std::string detail{"sys-savesync is not reporting"};
};

DisplayStatus read_status() {
    std::ifstream input(kStatusPath);
    if (!input) return {};
    const std::string json(
        (std::istreambuf_iterator<char>(input)),
        std::istreambuf_iterator<char>());
    DisplayStatus status;
    status.state = json_value(json, "state");
    status.active_title = json_value(json, "active_title_id");
    status.last_title = json_value(json, "last_title_id");
    status.detail = json_value(json, "detail");
    return status;
}

bool send_command(const char* action) {
    const std::string temporary = std::string(kCommandPath) + ".tmp";
    std::ofstream output(temporary, std::ios::trunc);
    if (!output) return false;
    output << "{\"action\":\"" << action << "\"}\n";
    output.close();
    if (!output) return false;
    std::remove(kCommandPath);
    return std::rename(temporary.c_str(), kCommandPath) == 0;
}

class SaveSyncGui final : public tsl::Gui {
public:
    tsl::elm::Element* createUI() override {
        const DisplayStatus status = read_status();
        auto* frame = new tsl::elm::OverlayFrame("NXSync", "Google Drive saves");
        auto* list = new tsl::elm::List();

        list->addItem(new tsl::elm::CategoryHeader("Status"));
        list->addItem(new tsl::elm::ListItem(status.state, status.detail));
        const std::string title =
            status.active_title != "0000000000000000"
                ? status.active_title
                : status.last_title;
        list->addItem(new tsl::elm::ListItem("Title ID", title));

        list->addItem(new tsl::elm::CategoryHeader("Actions"));
        auto* sync = new tsl::elm::ListItem("Sync Now");
        sync->setClickListener([](u64 keys) {
            return (keys & HidNpadButton_A) != 0 && send_command("sync");
        });
        list->addItem(sync);

        auto* pull = new tsl::elm::ListItem("Pull Remote Save");
        pull->setClickListener([](u64 keys) {
            return (keys & HidNpadButton_A) != 0 && send_command("pull");
        });
        list->addItem(pull);
        list->addItem(new tsl::elm::CategoryHeader(
            "Restore waits until the game closes"));
        frame->setContent(list);
        return frame;
    }
};

class SaveSyncOverlay final : public tsl::Overlay {
public:
    std::unique_ptr<tsl::Gui> loadInitialGui() override {
        return initially<SaveSyncGui>();
    }
};

} // namespace

int main(int argc, char** argv) {
    return tsl::loop<SaveSyncOverlay>(argc, argv);
}
