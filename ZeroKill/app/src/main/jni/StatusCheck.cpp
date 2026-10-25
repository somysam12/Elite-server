// StatusCheck.cpp — compiled in isolation with -mllvm -enable-strcry
// so the status URL gets AES encryption at build time.
#include <curl/curl.h>
#include <string>
#include <cstdlib>
#include <android/log.h>

#define SCLOG(...) __android_log_print(ANDROID_LOG_DEBUG, "KC_SC", __VA_ARGS__)

std::string g_tgUrlApk;
bool        g_tgRedirectApk = false;

static size_t _sc_write(void* ptr, size_t sz, size_t nmemb, std::string* out) {
    out->append((char*)ptr, sz * nmemb);
    return sz * nmemb;
}

__attribute__((noinline, annotate("bcf"), annotate("cff"), annotate("strcry")))
void CheckAppStatusNative() {
    CURL* c = curl_easy_init();
    if (!c) return;
    std::string resp;
    curl_easy_setopt(c, CURLOPT_URL,
        "https://github.com/somysam12/KingCheatsAuto/releases/download/KingCheats/status.txt");
    curl_easy_setopt(c, CURLOPT_WRITEFUNCTION, _sc_write);
    curl_easy_setopt(c, CURLOPT_WRITEDATA, &resp);
    curl_easy_setopt(c, CURLOPT_FOLLOWLOCATION, 1L);
    curl_easy_setopt(c, CURLOPT_CONNECTTIMEOUT, 8L);
    curl_easy_setopt(c, CURLOPT_TIMEOUT, 10L);
    curl_easy_setopt(c, CURLOPT_SSL_VERIFYPEER, 0L);
    curl_easy_setopt(c, CURLOPT_SSL_VERIFYHOST, 0L);
    curl_easy_setopt(c, CURLOPT_USERAGENT, "");
    CURLcode rc = curl_easy_perform(c);
    curl_easy_cleanup(c);
    if (rc != CURLE_OK || resp.empty()) return;

    // Parse ON/OFF
    bool on = true;
    size_t nl = 0;
    while (nl < resp.size()) {
        size_t e = resp.find('\n', nl);
        if (e == std::string::npos) e = resp.size();
        if (e - nl >= 4 && resp.substr(nl, 4) == "OFF ") { on = false; break; }
        nl = e + 1;
    }
    if (!on) { SCLOG("App OFF — exiting"); exit(0); }

    // Parse TELEGRAM LINK
    auto tlp = resp.find("TELEGRAM LINK : ");
    if (tlp != std::string::npos) {
        size_t s = tlp + 16, e = resp.find('\n', s);
        if (e == std::string::npos) e = resp.size();
        g_tgUrlApk = resp.substr(s, e - s);
        while (!g_tgUrlApk.empty() &&
               (g_tgUrlApk.back()=='\r'||g_tgUrlApk.back()=='\n'||g_tgUrlApk.back()==' '))
            g_tgUrlApk.pop_back();
    }

    // Parse TELEGRAM : ENABLE
    auto tep = resp.find("TELEGRAM : ");
    if (tep != std::string::npos && resp.substr(tep + 11, 6).find("ENABLE") != std::string::npos)
        g_tgRedirectApk = true;

    SCLOG("Status OK — tgRedirect=%d url=%s", (int)g_tgRedirectApk, g_tgUrlApk.c_str());
}
